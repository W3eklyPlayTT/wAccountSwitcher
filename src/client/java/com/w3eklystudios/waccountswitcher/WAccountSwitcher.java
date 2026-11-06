package com.w3eklystudios.waccountswitcher;

import net.fabricmc.api.ClientModInitializer;

public final class WAccountSwitcher implements ClientModInitializer {
    @Override public void onInitializeClient() { AccountStore.load(); }
}
