package com.dwinovo.numen.api.keys;

/**
 * 一位玩家用一个服务时找 key 的顺序:先找他自己的钥匙串,共享策略允许时再找服主的。找到的带着出处——服主的 key 花服主的钱,
 * 要受每人每天的 token 限额({@link com.dwinovo.numen.api.config.ServerSettings});主人自己的不受限。
 */
public final class Keys {

    /** key 从哪本钥匙串来。 */
    public enum Source { OWNER, SERVER }

    /** 找到的 key 与它的出处。key 不进日志,所以 {@code toString} 不带它。 */
    public record Found(String key, Source source) {

        @Override
        public String toString() {
            return "Found[" + source + "]";
        }
    }

    private final Keychain owner;
    private final Keychain server;
    private final java.util.function.BooleanSupplier sharedAllowed;

    public Keys(Keychain owner, Keychain server) {
        this(owner, server, () -> true);
    }

    public Keys(Keychain owner, Keychain server, java.util.function.BooleanSupplier sharedAllowed) {
        this.owner = owner;
        this.server = server;
        this.sharedAllowed = sharedAllowed;
    }

    /** 这个服务用哪把 key;两本里都没有是 {@code null}。 */
    public Found find(String service) {
        String own = owner.get(service);
        if (own != null) {
            return new Found(own, Source.OWNER);
        }
        if (!sharedAllowed()) {
            return null;
        }
        String shared = server.get(service);
        return shared == null ? null : new Found(shared, Source.SERVER);
    }

    /** 他自己的那本。 */
    public Keychain owner() {
        return owner;
    }

    /** 服主的那本。 */
    public Keychain server() {
        return server;
    }

    /** 此刻能否使用共享钥匙串,供设置页展示可用额度;不返回任何凭据。 */
    public boolean sharedAllowed() {
        return sharedAllowed.getAsBoolean();
    }
}
