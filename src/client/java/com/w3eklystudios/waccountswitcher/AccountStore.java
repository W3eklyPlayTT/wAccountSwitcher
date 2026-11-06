package com.w3eklystudios.waccountswitcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class AccountStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Account>>() {}.getType();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("waccountswitcher-accounts.json");
    private static final List<Account> ACCOUNTS = new ArrayList<>();
    private static int active = -1;

    static synchronized void load() {
        if (!Files.exists(FILE)) return;
        try {
            List<Account> list = GSON.fromJson(Files.readString(FILE), LIST_TYPE);
            if (list != null) ACCOUNTS.addAll(list);
        } catch (Exception e) { System.err.println("wAccountSwitcher: couldn't read saved accounts: " + e.getMessage()); }
    }
    static synchronized List<Account> all() { return new ArrayList<>(ACCOUNTS); }
    static synchronized int activeIndex() { return active; }
    static synchronized Account get(int i) { return i >= 0 && i < ACCOUNTS.size() ? ACCOUNTS.get(i) : null; }
    static synchronized int add(Account account) {
        for (int i = 0; i < ACCOUNTS.size(); i++) if (ACCOUNTS.get(i).name.equalsIgnoreCase(account.name)) {
            ACCOUNTS.set(i, account); save(); return i;
        }
        ACCOUNTS.add(account); save(); return ACCOUNTS.size() - 1;
    }
    static synchronized void remove(int i) {
        if (i < 0 || i >= ACCOUNTS.size()) return;
        ACCOUNTS.remove(i);
        if (active == i) active = -1; else if (active > i) active--;
        save();
    }
    static synchronized void update(int i, Account account) {
        if (i >= 0 && i < ACCOUNTS.size()) { ACCOUNTS.set(i, account); save(); }
    }
    static synchronized void setActive(int i) { active = i; }
    private static void save() {
        try { Files.createDirectories(FILE.getParent()); Files.writeString(FILE, GSON.toJson(ACCOUNTS, LIST_TYPE)); }
        catch (IOException e) { System.err.println("wAccountSwitcher: couldn't save accounts: " + e.getMessage()); }
    }
}
