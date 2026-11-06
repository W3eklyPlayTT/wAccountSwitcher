package com.w3eklystudios.waccountswitcher;

final class Account {
    String name;
    String uuid;
    String accessToken;
    String refreshToken;
    String xuid;
    String kind;

    Account() {}
    Account(String name, String uuid, String accessToken, String refreshToken, String xuid, String kind) {
        this.name = name; this.uuid = uuid; this.accessToken = accessToken;
        this.refreshToken = refreshToken; this.xuid = xuid; this.kind = kind;
    }
    boolean offline() { return "offline".equals(kind); }
}
