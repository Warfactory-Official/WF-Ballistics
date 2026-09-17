package com.wf.wfballistics.mine;

import it.unimi.dsi.fastutil.objects.Reference2BooleanMap;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * What trips a mine: a match over an entity's class, its {@link EntityType} tags, and any extra condition the
 * caller cares to write inline.
 */
public interface MineTarget {

    /**
     * @return true if {@code entity} trips a mine watching for this target. Callers that went through
     *      {@link #narrow()} still have to call this: the narrow class is a necessary condition, not a
     *      sufficient one.
     */
    boolean test(Entity entity);

    /** The most specific class every match is an instance of, for the entity lookup to filter on. */
    Class<? extends Entity> narrow();

    static Builder builder() {
        return new Builder();
    }

    /** A target that matches instances of one class and nothing else: the cheapest shape there is. */
    static MineTarget ofClass(Class<? extends Entity> type) {
        return builder().type(type)
                .build();
    }

    /** A target that matches any entity type carrying {@code tag}. */
    static MineTarget tagged(TagKey<EntityType<?>> tag) {
        return builder().tag(tag)
                .build();
    }

    final class Builder {

        private final List<Class<? extends Entity>> classes = new ArrayList<>();
        private final List<TagKey<EntityType<?>>> tags = new ArrayList<>();
        private final List<EntityType<?>> types = new ArrayList<>();

        @Nullable
        private Predicate<Entity> condition;

        private Builder() {
        }

        /** Match instances of {@code type} (ORed with the other kinds). */
        public Builder type(Class<? extends Entity> type) {
            if (!type.isInterface()) {
                this.classes.add(type);
            }
            return this;
        }

        /** Match any entity type carrying {@code tag} (ORed with the other kinds). */
        public Builder tag(TagKey<EntityType<?>> tag) {
            this.tags.add(tag);
            return this;
        }

        /** Match exactly this entity type (ORed with the other kinds). */
        public Builder entityType(EntityType<?> type) {
            this.types.add(type);
            return this;
        }

        /** An extra condition every match has to pass, ANDed with whatever else is declared. */
        public Builder where(Predicate<Entity> condition) {
            this.condition = this.condition == null ? condition : this.condition.and(condition);
            return this;
        }

        /**
         * Restrict to {@code type} and test it, in one: adds {@code type} as a kind so the broadphase can narrow on
         * it, and ANDs a condition that only instances of it can pass.
         */
        public <T extends Entity> Builder where(Class<T> type, Predicate<? super T> condition) {
            type(type);
            return where(entity -> type.isInstance(entity) && condition.test(type.cast(entity)));
        }

        public MineTarget build() {
            return new CompiledTarget(classes, tags, types, condition);
        }
    }

    /**
     * The built form: a narrow class, the kinds that class could not express, and the memo of what the type-only
     * kinds answered for each {@link EntityType} seen so far.
     */
    final class CompiledTarget implements MineTarget {

        private record Memo(int generation, Reference2BooleanMap<EntityType<?>> types) {
        }

        private final Class<? extends Entity> narrow;
        /** Empty when {@link #narrow} already is the whole class filter. */
        private final Class<?>[] classes;
        private final TagKey<EntityType<?>>[] tags;
        private final EntityType<?>[] types;
        @Nullable
        private final Predicate<Entity> condition;
        private final boolean anyKind;

        /**
         * Published as one object so a reader can never see a memo from one tag generation labelled with another.
         */
        private volatile Memo memo = new Memo(Integer.MIN_VALUE, new Reference2BooleanOpenHashMap<>());

        @SuppressWarnings("unchecked")
        private CompiledTarget(List<Class<? extends Entity>> classes, List<TagKey<EntityType<?>>> tags,
                               List<EntityType<?>> types, @Nullable Predicate<Entity> condition) {
            boolean byTypeOnly = tags.isEmpty() && types.isEmpty();
            if (byTypeOnly && classes.size() == 1) {
                // One class and nothing else: the broadphase can do the whole match.
                this.narrow = classes.get(0);
                this.classes = new Class<?>[0];
            } else {
                // A tagged type may be any class, so only an all-class target can narrow past Entity.
                this.narrow = byTypeOnly ? commonAncestor(classes) : Entity.class;
                this.classes = classes.toArray(new Class<?>[0]);
            }
            this.tags = tags.toArray(new TagKey[0]);
            this.types = types.toArray(new EntityType<?>[0]);
            this.condition = condition;
            this.anyKind = this.classes.length == 0 && this.tags.length == 0 && this.types.length == 0;
        }

        private static Class<? extends Entity> commonAncestor(List<Class<? extends Entity>> classes) {
            if (classes.isEmpty()) {
                return Entity.class;
            }
            Class<?> common = classes.get(0);
            for (int i = 1; i < classes.size(); i++) {
                Class<? extends Entity> other = classes.get(i);
                while (!common.isAssignableFrom(other)) {
                    common = common.getSuperclass();
                }
            }
            return common.asSubclass(Entity.class);
        }

        @Override
        public boolean test(Entity entity) {
            if (!this.narrow.isInstance(entity)) {
                return false;
            }
            if (!this.anyKind && !kindMatches(entity)) {
                return false;
            }
            return this.condition == null || this.condition.test(entity);
        }

        @Override
        public Class<? extends Entity> narrow() {
            return this.narrow;
        }

        private boolean kindMatches(Entity entity) {
            for (Class<?> type : this.classes) {
                if (type.isInstance(entity)) {
                    return true;
                }
            }
            return (this.tags.length != 0 || this.types.length != 0) && typeMatches(entity.getType());
        }

        /** Tag and exact-type membership, memoised: both are constant for a given {@link EntityType}. */
        private boolean typeMatches(EntityType<?> type) {
            int generation = MineTargets.tagGeneration();
            Memo current = this.memo;
            boolean fresh = current.generation() == generation;
            if (fresh && current.types()
                    .containsKey(type)) {
                return current.types()
                        .getBoolean(type);
            }

            boolean matches = computeTypeMatch(type);
            Reference2BooleanOpenHashMap<EntityType<?>> next = fresh
                    ? new Reference2BooleanOpenHashMap<>(current.types())
                    : new Reference2BooleanOpenHashMap<>();
            next.put(type, matches);
            this.memo = new Memo(generation, next);
            return matches;
        }

        private boolean computeTypeMatch(EntityType<?> type) {
            for (EntityType<?> exact : this.types) {
                if (exact == type) {
                    return true;
                }
            }
            for (TagKey<EntityType<?>> tag : this.tags) {
                if (type.is(tag)) {
                    return true;
                }
            }
            return false;
        }
    }
}
