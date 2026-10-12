package com.wf.wflib.client.map.xaero;

import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.mixin.xaero.worldmap.AccessorGuiMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@link MapMenu} onto Xaero's right-click options. Xaero menus are flat: a sub opens as a second menu at the
 * cursor, replacing the first.
 */
final class XaeroMenu implements MapMenu {

    private final GuiMap map;
    private final List<RightClickOption> options;
    private final IRightClickableElement target;
    private final BlockPos pos;

    XaeroMenu(GuiMap map, List<RightClickOption> options, IRightClickableElement target, BlockPos pos) {
        this.map = map;
        this.options = options;
        this.target = target;
        this.pos = pos;
    }

    @Override
    public void item(String label, Consumer<BlockPos> action) {
        this.options.add(new Item(label, this.options.size(), this.target) {
            @Override
            public void onAction(Screen screen) {
                action.accept(XaeroMenu.this.pos);
            }
        });
    }

    @Override
    public MapMenu sub(String label) {
        Sub sub = new Sub(label);
        this.options.add(new Item(label + " >", this.options.size(), this.target) {
            @Override
            public void onAction(Screen screen) {
                var window = Minecraft.getInstance().getWindow();
                var mouse = Minecraft.getInstance().mouseHandler;
                ((AccessorGuiMap) XaeroMenu.this.map).wflib$openMenu(sub,
                        (int) (mouse.xpos() / window.getGuiScale()), (int) (mouse.ypos() / window.getGuiScale()));
            }
        });
        return new XaeroMenu(this.map, sub.options, sub, this.pos);
    }

    /** Xaero translates option names: a literal label containing {@code %} would not survive. */
    private abstract static class Item extends RightClickOption {

        Item(String label, int index, IRightClickableElement target) {
            super(label, index, target);
        }

        @Override
        public Component getDisplayName() {
            return Component.literal(this.name).withStyle(this.style);
        }
    }

    /** Option 0 = title row, as in Xaero's own menus. */
    private static final class Sub implements IRightClickableElement {

        private final ArrayList<RightClickOption> options = new ArrayList<>();

        Sub(String title) {
            this.options.add(new Item(title, 0, this) {
                @Override
                public void onAction(Screen screen) {
                }
            });
        }

        @Override
        public ArrayList<RightClickOption> getRightClickOptions() {
            return this.options;
        }

        @Override
        public boolean isRightClickValid() {
            return true;
        }
    }
}
