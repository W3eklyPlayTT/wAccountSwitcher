package com.w3eklystudios.waccountswitcher;

import com.mojang.authlib.GameProfile;
import com.w3eklystudios.waccountswitcher.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.session.Session;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AccountSwitcherScreen extends Screen {
    private static final ExecutorService AUTH_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wAccountSwitcher-auth"); t.setDaemon(true); return t;
    });
    private final Screen parent;
    private final Map<String, net.minecraft.util.Identifier> skinHeads = new HashMap<>();
    private TextFieldWidget offlineName;
    private TextFieldWidget tokenField;
    private ButtonWidget copyCodeButton;
    private ButtonWidget openLinkButton;
    private String status = "Choose an account or add a new one.";
    private String deviceCodeText = "";
    private String verificationUri = "";
    private boolean busy;
    private boolean showToken;
    private int pageStart;
    private int panelLeft, panelTop, panelWidth, panelHeight;

    public AccountSwitcherScreen(Screen parent) { super(Text.literal("Account Switcher")); this.parent = parent; }

    @Override protected void init() {
        int cx = width / 2;
        panelWidth = Math.min(520, width - 24);
        panelHeight = Math.min(450, height - 20);
        panelLeft = cx - panelWidth / 2;
        panelTop = (height - panelHeight) / 2;
        int innerLeft = panelLeft + 24;
        int innerWidth = panelWidth - 48;
        int y = panelTop + 91;
        drawAccounts(innerLeft, innerWidth, y);

        offlineName = new TextFieldWidget(textRenderer, innerLeft, panelTop + panelHeight - 147,
                innerWidth - 106, 24, Text.literal("Minecraft username"));
        offlineName.setMaxLength(16);
        offlineName.setPlaceholder(Text.literal("Enter username"));
        addDrawableChild(offlineName);
        addDrawableChild(new RoundedButton(innerLeft + innerWidth - 98, panelTop + panelHeight - 147, 98, 24,
                "Add offline", b -> addOffline(), 0xFF3478F6));

        addDrawableChild(new RoundedButton(innerLeft, panelTop + panelHeight - 116, innerWidth, 28,
                busy ? "Connecting…" : "Continue with Microsoft", b -> microsoftLogin(), 0xFF3478F6));

        copyCodeButton = new RoundedButton(innerLeft, panelTop + panelHeight - 83, (innerWidth - 8) / 2, 24,
                "Copy sign-in code", b -> copyCode(), 0xFF414B5A);
        openLinkButton = new RoundedButton(innerLeft + (innerWidth + 8) / 2, panelTop + panelHeight - 83,
                (innerWidth - 8) / 2, 24, "Open Microsoft page", b -> openMicrosoftPage(), 0xFF414B5A);
        copyCodeButton.visible = !deviceCodeText.isEmpty();
        openLinkButton.visible = !verificationUri.isEmpty();
        addDrawableChild(copyCodeButton);
        addDrawableChild(openLinkButton);

        if (showToken) {
            tokenField = new TextFieldWidget(textRenderer, innerLeft, panelTop + panelHeight - 52,
                    innerWidth - 112, 24, Text.literal("Session token"));
            tokenField.setMaxLength(4096);
            tokenField.setPlaceholder(Text.literal("Advanced: paste a session token"));
            addDrawableChild(tokenField);
            addDrawableChild(new RoundedButton(innerLeft + innerWidth - 104, panelTop + panelHeight - 52, 104, 24,
                    "Check token", b -> addToken(), 0xFF414B5A));
        } else {
            addDrawableChild(new RoundedButton(innerLeft, panelTop + panelHeight - 52, innerWidth - 112, 24,
                    "Advanced: session token", b -> { showToken = true; clearAndInit(); }, 0xFF303744));
        }
        addDrawableChild(new RoundedButton(cx - 54, panelTop + panelHeight - 22, 108, 24,
                "Done", b -> close(), 0xFF414B5A));
    }

    private ButtonWidget button(String label, ButtonWidget.PressAction action, int x, int y, int w, int h) {
        return ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, h).build();
    }

    private void drawAccounts(int x, int innerWidth, int y) {
        var accounts = AccountStore.all();
        int lastPageStart = accounts.isEmpty() ? 0 : ((accounts.size() - 1) / 3) * 3;
        pageStart = Math.max(0, Math.min(pageStart, lastPageStart));
        int shown = Math.min(3, accounts.size() - pageStart);
        if (shown == 0) return;
        for (int i = 0; i < shown; i++) {
            int accountIndex = pageStart + i;
            Account account = accounts.get(accountIndex);
            int rowY = y + i * 50;
            boolean active = accountIndex == AccountStore.activeIndex();
            ButtonWidget head = button("", b -> { switchTo(accountIndex); status = "Now using " + account.name + "."; }, x, rowY, 42, 42);
            addDrawableChild(head);
            addDrawableChild(button((active ? "✓  " : "") + account.name + "  ·  " + kindLabel(account), b -> { switchTo(accountIndex); status = "Now using " + account.name + "."; }, x + 50, rowY, innerWidth - 160, 42));
            addDrawableChild(button("↻", b -> refresh(accountIndex), x + innerWidth - 102, rowY, 50, 42));
            addDrawableChild(button("×", b -> { AccountStore.remove(accountIndex); clearAndInit(); }, x + innerWidth - 46, rowY, 46, 42));
        }
        if (accounts.size() > 3) {
            if (pageStart > 0) addDrawableChild(button("‹ Previous", b -> { pageStart = Math.max(0, pageStart - 3); clearAndInit(); }, x, y + 3 * 50, 90, 22));
            if (pageStart + shown < accounts.size()) addDrawableChild(button("Next ›", b -> { pageStart += 3; clearAndInit(); }, x + innerWidth - 90, y + 3 * 50, 90, 22));
        }
    }

    private static String kindLabel(Account a) {
        return a.offline() ? "Offline" : "microsoft".equals(a.kind) ? "Microsoft" : "Session token";
    }

    private void addOffline() {
        String name = offlineName.getText().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) { status = "Username needs 3–16 letters, numbers or underscores."; return; }
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        int i = AccountStore.add(new Account(name, uuid, "", null, null, "offline"));
        switchTo(i); clearAndInit();
    }

    private void addToken() {
        String token = tokenField.getText().trim();
        if (token.isEmpty()) { status = "Paste a Minecraft session token first."; return; }
        runAuth("Checking session token…", () -> MicrosoftAuth.tokenLogin(token), account -> {
            int i = AccountStore.add(account); switchTo(i); clearAndInit();
        });
    }

    private void microsoftLogin() {
        if (busy) return;
        busy = true; status = "Starting Microsoft sign-in…";
        AUTH_WORKER.execute(() -> {
            try {
                MicrosoftAuth.DeviceCode device = MicrosoftAuth.startDeviceCode();
                MinecraftClient.getInstance().execute(() -> {
                    deviceCodeText = device.userCode(); verificationUri = device.verificationUri();
                    status = "1. Open the Microsoft page.  2. Enter the code below.  3. Approve it.";
                    copyCodeButton.visible = true; openLinkButton.visible = true;
                    try { Util.getOperatingSystem().open(device.verificationUri()); }
                    catch (Exception ignored) { status = "Open the Microsoft page below and enter the code."; }
                });
                Account account = MicrosoftAuth.completeDeviceCode(device, message -> MinecraftClient.getInstance().execute(() -> status = message));
                MinecraftClient.getInstance().execute(() -> {
                    busy = false; int i = AccountStore.add(account); switchTo(i);
                    status = "Connected! Now using " + account.name + "."; deviceCodeText = ""; verificationUri = ""; clearAndInit();
                });
            } catch (Exception e) { finishError(e); }
        });
    }

    private void copyCode() {
        if (deviceCodeText.isEmpty()) return;
        GLFW.glfwSetClipboardString(client.getWindow().getHandle(), deviceCodeText);
        status = "Code copied. Paste it on the Microsoft page to continue.";
    }

    private void openMicrosoftPage() {
        if (verificationUri.isEmpty()) return;
        Util.getOperatingSystem().open(verificationUri);
        status = "Enter the code shown here on Microsoft's page.";
    }

    private void refresh(int index) {
        Account account = AccountStore.get(index);
        if (account == null) return;
        if (account.offline() || "token".equals(account.kind)) { status = "This account cannot refresh. Add it again with Microsoft sign-in."; return; }
        runAuth("Refreshing " + account.name + "…", () -> MicrosoftAuth.refresh(account), updated -> {
            AccountStore.update(index, updated); switchTo(index); status = "Account refreshed."; clearAndInit();
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
        if (msg.contains("2148916233")) msg = "This Microsoft account needs an Xbox profile before it can play Minecraft Java.";
        else if (msg.contains("2148916238")) msg = "Xbox is unavailable for this child account until a parent completes setup.";
        else if (msg.contains("404") || msg.toLowerCase().contains("minecraft profile")) msg = "No Minecraft Java profile found. Check that this Microsoft account owns Java Edition.";
        if (msg.length() > 115) msg = msg.substring(0, 112) + "…";
        String finalMsg = msg;
        MinecraftClient.getInstance().execute(() -> { busy = false; status = "Sign-in didn't finish: " + finalMsg; });
    }

    private void switchTo(int index) {
        Account account = AccountStore.get(index);
        if (account == null) return;
        try {
            var uuid = UUID.fromString(account.uuid.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})", "$1-$2-$3-$4-$5"));
            ((MinecraftClientAccessor) MinecraftClient.getInstance()).waccountswitcher$setSession(new Session(
                    account.name, uuid, account.accessToken == null ? "" : account.accessToken,
                    Optional.empty(), Optional.ofNullable(account.xuid)));
            AccountStore.setActive(index);
        } catch (Exception e) { status = "Couldn't switch account: " + e.getMessage(); }
    }

    private void loadSkin(Account account) {
        if (skinHeads.containsKey(account.uuid)) return;
        try {
            UUID uuid = UUID.fromString(account.uuid.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})", "$1-$2-$3-$4-$5"));
            MinecraftClient.getInstance().getSkinProvider().fetchSkinTextures(new GameProfile(uuid, account.name))
                    .thenAccept(result -> result.ifPresent(textures -> MinecraftClient.getInstance().execute(() ->
                            skinHeads.put(account.uuid, textures.body().texturePath()))));
            skinHeads.put(account.uuid, null);
        } catch (Exception ignored) { skinHeads.put(account.uuid, null); }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xB8000000);
        rounded(context, panelLeft, panelTop, panelWidth, panelHeight, 16, 0xF21B2029);
        rounded(context, panelLeft + 1, panelTop + 1, panelWidth - 2, 52, 15, 0xFF252D39);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelTop + 15, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Switch or add an account in a few clicks"), width / 2, panelTop + 36, 0xFFBCC7D5);
        String shownStatus = status;
        if (textRenderer.getWidth(shownStatus) > panelWidth - 28) shownStatus = textRenderer.trimToWidth(shownStatus, panelWidth - 36) + "…";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(shownStatus), width / 2, panelTop + 66, status.startsWith("Sign-in didn't") ? 0xFFFF8C8C : 0xFFDFE6EF);
        for (int i = 0; i < 3 && pageStart + i < AccountStore.all().size(); i++) {
            int rowY = panelTop + 91 + i * 50;
            rounded(context, panelLeft + 24, rowY - 2, panelWidth - 48, 46, 9, 0xFF252D39);
        }
        if (AccountStore.all().isEmpty()) {
            rounded(context, panelLeft + 24, panelTop + 91, panelWidth - 48, 42, 10, 0xFF252D39);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("No accounts yet — add one below"), width / 2, panelTop + 106, 0xFFAAB6C5);
        }
        if (!deviceCodeText.isEmpty()) {
            rounded(context, panelLeft + 24, panelTop + panelHeight - 182, panelWidth - 48, 31, 9, 0xFF343021);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Your code:  " + deviceCodeText), width / 2, panelTop + panelHeight - 172, 0xFFFFDF68);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Keep this screen open while you approve sign-in."), width / 2, panelTop + panelHeight - 159, 0xFFCFD5DE);
        }
        super.render(context, mouseX, mouseY, delta);
        for (int i = 0; i < 3 && pageStart + i < AccountStore.all().size(); i++) {
            Account account = AccountStore.get(pageStart + i); if (account == null) continue;
            int x = panelLeft + 24, y = panelTop + 91 + i * 50;
            net.minecraft.util.Identifier texture = skinHeads.get(account.uuid);
            if (texture != null) {
                context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x + 5, y + 5, 8, 8, 8, 8, 64, 64);
                context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x + 5, y + 5, 40, 8, 8, 8, 64, 64);
            }
            else { context.fill(x + 10, y + 9, x + 32, y + 31, 0xFF93A1B3); context.fill(x + 14, y + 14, x + 18, y + 18, 0xFF414B5A); context.fill(x + 24, y + 14, x + 28, y + 18, 0xFF414B5A); }
        }
    }

    private static void rounded(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        int r = Math.min(radius, Math.min(w, h) / 2);
        for (int row = 0; row < h; row++) {
            int inset = 0;
            if (row < r) inset = r - (int) Math.sqrt(Math.max(0, r * r - (r - row) * (r - row)));
            else if (row >= h - r) { int d = row - (h - r - 1); inset = r - (int) Math.sqrt(Math.max(0, r * r - d * d)); }
            ctx.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
        }
    }

    private final class SkinHeadButton extends ButtonWidget {
        SkinHeadButton(int x, int y, int w, int h, Account account, boolean active, PressAction action) {
            super(x, y, w, h, net.minecraft.text.Text.literal(""), action, DEFAULT_NARRATION_SUPPLIER);
            loadSkin(account);
        }
        @Override protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {}
    }

    private final class RoundedButton extends ButtonWidget {
        RoundedButton(int x, int y, int w, int h, String label, PressAction action, int background) {
            super(x, y, w, h, net.minecraft.text.Text.literal(label), action, DEFAULT_NARRATION_SUPPLIER);
        }
        @Override protected void drawIcon(DrawContext context, int mouseX, int mouseY, float delta) {}
    }

    @Override public void close() { client.setScreen(parent); }
    @FunctionalInterface private interface AuthAction { Account run() throws Exception; }
}
