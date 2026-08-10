package com.wf.wfballistics.menu;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, WFBallistics.MODID);

    public static void register(IEventBus bus) {
        MENUS.register(bus);
    }

    public static final DeferredHolder<MenuType<?>, MenuType<MissileDispenserMenu>> MISSILE_DISPENSER =
            MENUS.register("missile_dispenser", () -> IMenuTypeExtension.create(MissileDispenserMenu::new));


}
