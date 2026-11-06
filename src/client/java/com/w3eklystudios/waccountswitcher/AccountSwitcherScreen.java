package com.w3eklystudios.waccountswitcher;

import com.w3eklystudios.waccountswitcher.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.session.Session;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AccountSwitcherScreen extends Screen {
    private static final ExecutorService AUTH_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wAccountSwitcher-auth"); t.setDaemon(true); return t;
    });
    private final Screen parent;
    private TextFieldWidget offlineName;
    private TextFieldWidget tokenField;
    private String status = "Choose a saved account or add one below.";
    private String deviceCodeText = "";
    private String verificationUri = "";
    private boolean busy;
    private int pageStart;

    public AccountSwitcherScreen(Screen parent) { super(Text.literal("Account Switcher")); this.parent = parent; }

    @Override protected void init() {
        int cx = width / 2;
        drawAccounts(cx);
        offlineName = new TextFieldWidget(textRenderer, cx - 150, 120, 150, 20, Text.literal("Offline username"));
        offlineName.setMaxLength(16);
        addDrawableChild(offlineName);
        addDrawableChild(ButtonWidget.builder(Text.literal("Add offline"), b -> addOffline())
                .dimensions(cx + 5, 120, 95, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Microsoft sign-in"), b -> microsoftLogin())
                .dimensions(cx - 150, 144, 250, 20).build());
        tokenField = new TextFieldWidget(textRenderer, cx - 150, 168, 250, 20, Text.literal("Minecraft session access token"));
        tokenField.setMaxLength(4096);
        tokenField.setPlaceholder(Text.literal("Session access token"));
        addDrawableChild(tokenField);
        addDrawableChild(ButtonWidget.builder(Text.literal("Add session token"), b -> addToken())
                .dimensions(cx - 150, 192, 250, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(cx - 100, height - 30, 200, 20).build());
    }

    private void drawAccounts(int cx) {
        var accounts = AccountStore.all();
        int lastPageStart = accounts.isEmpty() ? 0 : ((accounts.size() - 1) / 3) * 3;
        pageStart = Math.max(0, Math.min(pageStart, lastPageStart));
        int shown = Math.min(3, accounts.size() - pageStart);
        if (shown == 0) {
            return;
        }
        for (int i = 0; i < shown; i++) {
            int accountIndex = pageStart + i;
            Account account = accounts.get(accountIndex);
            int y = 48 + i * 22;
            String label = account.name + (accountIndex == AccountStore.activeIndex() ? "  ✓" : "");
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> switchTo(accountIndex))
                    .dimensions(cx - 150, y, 145, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Refresh"), b -> refresh(accountIndex))
                    .dimensions(cx, y, 65, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Remove"), b -> { AccountStore.remove(accountIndex); clearAndInit(); })
                    .dimensions(cx + 70, y, 80, 20).build());
        }
        if (pageStart > 0) addDrawableChild(ButtonWidget.builder(Text.literal("‹"), b -> { pageStart = Math.max(0, pageStart - 3); clearAndInit(); })
                .dimensions(cx - 176, 48, 20, 64).build());
        if (pageStart + shown < accounts.size()) addDrawableChild(ButtonWidget.builder(Text.literal("›"), b -> { pageStart += 3; clearAndInit(); })
                .dimensions(cx + 154, 48, 20, 64).build());
    }

    private void addOffline() {
        String name = offlineName.getText().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) { status = "Use a 3–16 character Minecraft username."; return; }
        String uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        int i = AccountStore.add(new Account(name, uuid, "", null, null, "offline"));
        switchTo(i);
        clearAndInit();
    }

    private void addToken() {
        String token = tokenField.getText().trim();
        if (token.isEmpty()) { status = "Paste a Minecraft session access token."; return; }
        runAuth("Checking Minecraft session token…", () -> MicrosoftAuth.tokenLogin(token), account -> {
            int i = AccountStore.add(account); switchTo(i); clearAndInit();
        });
    }

    private void microsoftLogin() {
        busy = true; status = "Requesting a Microsoft sign-in code…";
        runOnClient();
        AUTH_WORKER.execute(() -> {
            try {
                MicrosoftAuth.DeviceCode device = MicrosoftAuth.startDeviceCode();
                MinecraftClient.getInstance().execute(() -> {
                    deviceCodeText = "Microsoft code: " + device.userCode();
                    verificationUri = device.verificationUri();
                    status = "Enter the code shown below in the browser. Waiting…";
                    Util.getOperatingSystem().open(device.verificationUri());
                });
                Account account = MicrosoftAuth.completeDeviceCode(device, message -> MinecraftClient.getInstance().execute(() -> status = message));
                MinecraftClient.getInstance().execute(() -> {
                    busy = false; int i = AccountStore.add(account); switchTo(i);
                    status = "Microsoft account added: " + account.name; deviceCodeText = ""; verificationUri = ""; clearAndInit();
                });
            } catch (Exception e) { finishError(e); }
        });
    }

    private void refresh(int index) {
        Account account = AccountStore.get(index);
        if (account == null) return;
        if (account.offline() || "token".equals(account.kind)) { status = "This account type has no refresh token. Re-add it with Microsoft sign-in."; return; }
        runAuth("Refreshing " + account.name + "…", () -> MicrosoftAuth.refresh(account), updated -> {
            AccountStore.update(index, updated); switchTo(index); status = "Refreshed " + updated.name + "."; clearAndInit();
        });
    }

    private void runAuth(String message, AuthAction action, java.util.function.Consumer<Account> success) {
        if (busy) return;
        busy = true; status = message;
        AUTH_WORKER.execute(() -> {
            try { Account result = action.run(); MinecraftClient.getInstance().execute(() -> { busy = false; success.accept(result); }); }
            catch (Exception e) { finishError(e); }
        });
    }

    private void finishError(Exception e) {
        String msg = e.getMessage() == null ? "Authentication failed." : e.getMessage();
        if (msg.length() > 110) msg = msg.substring(0, 107) + "…";
        String finalMsg = msg;
        MinecraftClient.getInstance().execute(() -> { busy = false; status = "Sign-in failed: " + finalMsg; });
    }

    private void switchTo(int index) {
        Account account = AccountStore.get(index);
        if (account == null) return;
        try {
            var uuid = java.util.UUID.fromString(account.uuid.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})", "$1-$2-$3-$4-$5"));
            ((MinecraftClientAccessor) MinecraftClient.getInstance()).waccountswitcher$setSession(new Session(
                    account.name, uuid, account.accessToken == null ? "" : account.accessToken,
                    Optional.empty(), Optional.ofNullable(account.xuid)));
            AccountStore.setActive(index);
            status = "Switched to " + account.name + ".";
        } catch (Exception e) { status = "Couldn't apply account: " + e.getMessage(); }
    }

    private void runOnClient() { }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xD0101010);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 37, 0xDDDDDD);
        if (AccountStore.all().isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.literal("No saved accounts yet"), width / 2, 64, 0xAAAAAA);
        if (!deviceCodeText.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.literal(deviceCodeText), width / 2, 218, 0xFFFF55);
        if (!verificationUri.isEmpty()) {
            String browserLine = "Browser: " + verificationUri;
            if (textRenderer.getWidth(browserLine) > width - 16) browserLine = textRenderer.trimToWidth(browserLine, width - 24) + "…";
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(browserLine), width / 2, 230, 0xFFFF55);
        }
        super.render(context, mouseX, mouseY, delta);
    }
    @Override public void close() { client.setScreen(parent); }
    @FunctionalInterface private interface AuthAction { Account run() throws Exception; }
}
