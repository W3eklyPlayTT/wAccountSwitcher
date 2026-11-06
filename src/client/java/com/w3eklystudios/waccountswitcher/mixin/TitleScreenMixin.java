package com.w3eklystudios.waccountswitcher.mixin;

import com.w3eklystudios.waccountswitcher.AccountSwitcherScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void waccountswitcher$button(CallbackInfo ci) {
        TitleScreen parent = (TitleScreen)(Object)this;
        ((ScreenInvoker)(Object) parent).waccountswitcher$addDrawableChild(ButtonWidget.builder(Text.literal("Accounts"), b -> MinecraftClient.getInstance().setScreen(new AccountSwitcherScreen(parent)))
                .dimensions(parent.width / 2 + 105, parent.height / 4 + 48, 90, 20).build());
    }
}
