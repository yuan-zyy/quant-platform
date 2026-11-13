package io.github.quant.risk;

import io.github.quant.broker.spi.BrokerAdapter;
import io.github.quant.risk.PreTradeRiskPolicy.InstrumentKey;
import io.github.quant.risk.PreTradeRiskPolicy.Limits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static io.github.quant.risk.PreTradeRiskPolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class PreTradeRiskPolicyTest {
    private static final InstrumentKey SHANGHAI = new InstrumentKey("SSE", "600000");
    private static final InstrumentKey SHENZHEN = new InstrumentKey("SZSE", "600000");

    @Test
    void permitsInclusiveLimitsAndUsesExactDecimalNotional() {
        var policy = new PreTradeRiskPolicy(Map.of("account-a",
                Map.of(SHANGHAI, new Limits(100, new BigDecimal("1000.000")))));

        assertEquals(ALLOWED, policy.evaluate(request("account-a", SHANGHAI, 100, "10.00")));
        assertEquals(NOTIONAL_LIMIT_EXCEEDED,
                policy.evaluate(request("account-a", SHANGHAI, 100, "10.0000000000000000001")));
        assertEquals(QUANTITY_LIMIT_EXCEEDED, policy.evaluate(request("account-a", SHANGHAI, 101, "10")));
        assertEquals(QUANTITY_LIMIT_EXCEEDED, policy.evaluate(request("account-a", SHANGHAI, Long.MAX_VALUE, "0.01")));
    }

    @Test
    void isolatesAccountsAndInstrumentsByExchangeAndSymbol() {
        var policy = new PreTradeRiskPolicy(Map.of(
                "account-a", Map.of(SHANGHAI, new Limits(200, new BigDecimal("2000"))),
                "account-b", Map.of(SHANGHAI, new Limits(100, new BigDecimal("1000")),
                        SHENZHEN, new Limits(200, new BigDecimal("2000")))));

        assertEquals(ACCOUNT_NOT_CONFIGURED, policy.evaluate(request("missing", SHANGHAI, 100, "10")));
        assertEquals(INSTRUMENT_NOT_CONFIGURED, policy.evaluate(request("account-a", SHENZHEN, 100, "10")));
        assertEquals(ALLOWED, policy.evaluate(request("account-a", SHANGHAI, 150, "10")));
        assertEquals(QUANTITY_LIMIT_EXCEEDED, policy.evaluate(request("account-b", SHANGHAI, 150, "10")));
        assertEquals(ALLOWED, policy.evaluate(request("account-b", SHENZHEN, 150, "10")));
    }

    @Test
    void validatesAndCopiesConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new InstrumentKey(" ", "600000"));
        assertThrows(IllegalArgumentException.class, () -> new PreTradeRiskPolicy(
                Map.of(" ", Map.of(SHANGHAI, new Limits(1, BigDecimal.ONE)))));

        var instruments = new HashMap<>(Map.of(SHANGHAI, new Limits(100, new BigDecimal("1000"))));
        var accounts = new HashMap<String, Map<InstrumentKey, Limits>>();
        accounts.put("account-a", instruments);
        var policy = new PreTradeRiskPolicy(accounts);
        instruments.clear();
        accounts.clear();
        assertEquals(ALLOWED, policy.evaluate(request("account-a", SHANGHAI, 100, "10")));
    }

    private static BrokerAdapter.Submission request(String account, InstrumentKey instrument, long quantity,
                                                    String price) {
        return new BrokerAdapter.Submission(new BrokerAdapter.SubmissionKey(account, "test-order"),
                instrument.exchange(), instrument.symbol(), BrokerAdapter.Side.BUY, quantity, new BigDecimal(price));
    }
}
