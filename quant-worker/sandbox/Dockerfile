FROM python:3.12-slim
WORKDIR /opt/strategy
COPY python-sdk/quant_strategy /opt/strategy/quant_strategy
COPY quant-worker/sandbox/launch.py quant-worker/sandbox/smoke_strategy.py /opt/strategy/
USER 65532:65532
