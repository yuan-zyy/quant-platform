# Contributing

- Branch from develop; keep PRs focused.
- Use Java 21. Never use double for money/prices; use BigDecimal or fixed-point integers.
- Model eventTime, availableTime and processingTime explicitly.
- Add tests for behavior and order state transitions.
- Never commit credentials, personal data or unlicensed market data.
- Run `mvn -B verify` before merge.
