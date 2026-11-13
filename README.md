# Quant Platform

Java 21 + Python 量化研究与交易平台，面向中国 A 股、ETF、可转债。当前支持确定性日线回测，并可在独立子进程中运行受信任的 Python 策略。

## Build

`mvn -B -ntp verify`

安装 Python 3.12 后，可运行 Java 与 Python 的端到端回测验证：

`mvn -B -ntp -Dquant.python=python verify`

## Docs

- [Architecture](docs/ARCHITECTURE.md)
- [Tasks](docs/TASKS.md)
- [Decisions](docs/DECISIONS.md)
- [Sprint 1 日线回测](docs/SPRINT-1.md)
- [Sprint 2 Python 策略执行](docs/SPRINT-2.md)
- [Sprint 3 历史行情与时点快照](docs/SPRINT-3.md)
