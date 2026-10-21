package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwinovo.numen.e2e.FakeModel.say;

class OwnerCliTest extends Scenario {
    @Test
    void aPublicProfileCannotChargeTheServerOrAnotherPlayersKey() throws Exception {
        Bot owner = join("cli_unfunded", "zh_cn"), funded = join("cli_funded", "zh_cn");
        int mark = funded.mark(); funded.command("numen key custom " + SPEC.modelKey());
        funded.await(mark, m -> m.text().contains("custom"), WAIT, "other player's private test key");
        try {
            mark = owner.mark(); owner.command("numen player summon PolicyPet");
            owner.await(mark, m -> m.text().contains("PolicyPet") && m.text().contains("召唤"), WAIT, "unfunded companion");
            mark = owner.mark(); owner.command("numen companion PolicyPet profile " + SPEC.modelProfile());
            owner.await(mark, m -> m.text().contains(SPEC.modelProfile()), WAIT, "public profile without shared spending rights");
            owner.command("msg PolicyPet no-shared-charge");
            long deadline = System.nanoTime() + WAIT.toNanos();
            String state;
            do {
                mark = owner.mark(); owner.command("numen status");
                state = owner.await(mark, m -> m.text().contains("PolicyPet"), WAIT, "fresh brain state").text();
                assertTrue(System.nanoTime() < deadline, "未获得模型未配置的最终阻塞回执: " + state);
            } while (!state.contains("BLOCKED"));
            assertTrue(state.contains("密钥"), "明确报告缺少密钥而非泛化失败");
            assertTrue(MODEL.asked().isEmpty(), "共享Key和另一个主人的Key均不能被使用");
        } finally {
            owner.command("numen player despawn PolicyPet");
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (console("list").contains("PolicyPet")) {
                assertTrue(System.nanoTime() < deadline); Thread.sleep(100);
            }
        }
    }

    @Test
    void vanillaSettingsAndStatusArePrivateReadOnlyAndNeverRequireAMod() throws Exception {
        Bot owner = join("cli_reader", "zh_cn"), other = join("cli_bystander", "zh_cn");
        int bystander = other.mark(), mark = owner.mark();
        owner.command("numen settings");
        owner.await(mark, m -> m.text().contains("/numen model set"), WAIT, "vanilla CLI help");
        owner.command("numen status");
        owner.await(mark, m -> m.text().contains("共享额度=关闭"), WAIT, "private cost policy");
        owner.command("numen model list");
        owner.await(mark, m -> m.text().contains(SPEC.modelProfile()), WAIT, "native model catalog");
        assertTrue(owner.payloadChannels().stream().noneMatch(c -> c.startsWith("numen_api:")));
        assertTrue(MODEL.asked().isEmpty(), "查询不能偷偷调用模型");
        int barrier = other.mark(); other.command("numen status");
        other.await(barrier, m -> m.text().contains("共享额度=关闭"), WAIT, "ordered private query");
        assertTrue(other.since(bystander).stream().noneMatch(m -> m.text().contains("/numen model set") || m.text().contains(SPEC.modelProfile())));
    }

    @Test
    void vanillaPlayersCanSaveTheirOwnModelAndRunItWithoutBorrowingAServerKey() throws Exception {
        Bot owner = join("cli_model", "zh_cn"), other = join("cli_other", "zh_cn");
        String set = "numen model set own custom chat e2e-fake http://127.0.0.1:" + SPEC.modelPort() + "/v1";
        for (int i = 0; i < 2; i++) {
            int mark = owner.mark(); owner.command(set);
            owner.await(mark, m -> m.text().contains("已保存并核对：my:own"), WAIT, "durable profile update");
        }
        int mark = owner.mark(); owner.command("numen model list");
        owner.await(mark, m -> m.text().contains("my:own") && m.text().contains("未配置"), WAIT, "no shared key fallback");
        assertTrue(owner.since(mark).stream().noneMatch(m -> m.text().contains("my:own_2")), "重复set更新同一条目");
        mark = other.mark(); other.command("numen model list");
        other.await(mark, m -> m.text().contains(SPEC.modelProfile()), WAIT, "other catalog");
        assertTrue(other.since(mark).stream().noneMatch(m -> m.text().contains("my:own")));
        summon(owner, "CliModelPet");
        mark = owner.mark(); owner.command("numen companion CliModelPet profile my:own");
        owner.await(mark, m -> m.text().contains("my:own"), WAIT, "bind own model");
        MODEL.whenHeard("own-key-check", say("本人模型运行成功"));
        mark = owner.mark(); owner.command("msg CliModelPet own-key-check");
        owner.await(mark, m -> m.text().contains("本人模型运行成功"), WAIT, "actual model roundtrip");
        assertTrue(MODEL.wasToldAbout("own-key-check"));
    }

    @Test
    void invalidModelInputDoesNotCreateAProfileOrExposeCredentials() throws Exception {
        Bot owner = join("cli_invalid", "zh_cn");
        int mark = owner.mark();
        owner.command("numen model set invalid custom chat test https://example.test/v1?key=not-a-real-secret");
        owner.await(mark, m -> m.text().contains("档案格式不正确"), WAIT, "private rejection");
        assertTrue(owner.since(mark).stream().noneMatch(m -> m.text().contains("not-a-real-secret")));
        mark = owner.mark(); owner.command("numen model list");
        owner.await(mark, m -> m.text().contains(SPEC.modelProfile()), WAIT, "unchanged catalog");
        assertTrue(owner.since(mark).stream().noneMatch(m -> m.text().contains("my:invalid")));
        assertTrue(MODEL.asked().isEmpty());
    }
}
