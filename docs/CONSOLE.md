# Run the local backtest console

The Vue console submits a registered scenario, polls the task until it completes, and can cancel a task that is still active. The built-in demo scenario uses two historical opening observations and a fixed Java strategy. It runs the same simulated backtest API as the service tests.

Install Java 21, Maven, and Node.js 22 or later. From the repository root build the demo server:

```sh
mvn -B -ntp -pl quant-server -am package
```

In one terminal, set a local demo token and start the server on loopback:

```sh
export QUANT_DEMO_TOKEN='replace-with-your-local-demo-token'
java -cp 'quant-server/target/classes:quant-backtest/target/classes:quant-domain/target/classes:quant-event/target/classes:quant-market/target/classes:quant-oms/target/classes:quant-portfolio/target/classes' io.github.quant.server.demo.BacktestDemoServer
```

In PowerShell, use `$env:QUANT_DEMO_TOKEN` and replace the classpath separators with `;`:

```powershell
$env:QUANT_DEMO_TOKEN = 'replace-with-your-local-demo-token'
$classpath = 'quant-server/target/classes;quant-backtest/target/classes;quant-domain/target/classes;quant-event/target/classes;quant-market/target/classes;quant-oms/target/classes;quant-portfolio/target/classes'
java -cp $classpath io.github.quant.server.demo.BacktestDemoServer
```

In a second terminal, install the console dependencies and start Vite:

```sh
cd web-console
npm ci
npm run dev
```

Open the local address printed by Vite. Enter the same demo token and run `demo-buy-hold`. To exercise cancellation, submit `demo-cancel` and cancel it while active. The browser keeps the token in memory during the page session. The Vite proxy forwards API calls to the Java server at `127.0.0.1:8080`. If you change `QUANT_DEMO_PORT`, set it to the same value in both terminals. Use a demo token of 16 to 128 characters. The demo uses a single configured tenant and local transport.

To start the server using token fingerprints, set `QUANT_DEMO_CREDENTIALS` to a protected UTF-8 file and omit `QUANT_DEMO_TOKEN` from the server environment. Write one line with the SHA-256 hex digest of the token, a space and `local-demo`. Enter the original token in the console. The server reads the file at startup and rejects malformed or repeated fingerprints.
