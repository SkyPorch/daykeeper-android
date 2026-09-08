import assert from "node:assert/strict";
import test from "node:test";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { EventEmitter } from "node:events";
import {
  choosePort,
  runIsolated,
  terminateOwnChild,
} from "./run-isolated-instrumentation.mjs";
import {
  assertExactSerial,
  assertOwnedAvd,
  assertOwnedEmulator,
  cleanupOwnedAvd,
} from "./run-isolated-instrumentation.mjs";

test("serial guard accepts only the exact ready device", () => {
  assertExactSerial(
    "List of devices attached\nemulator-5600\tdevice\n",
    "emulator-5600",
  );
  assert.throws(() =>
    assertExactSerial(
      "List of devices attached\nemulator-5602\tdevice\n",
      "emulator-5600",
    ),
  );
});

test("invalid preflight cannot create or start an emulator", async () => {
  let mutations = 0;
  const forbidden = () => {
    mutations++;
    throw new Error("unexpected mutation");
  };
  await assert.rejects(
    runIsolated(
      {
        execute: true,
        sdkRoot: "/nonexistent-daykeeper-sdk",
        image: "../../unsafe",
      },
      forbidden,
      forbidden,
      forbidden,
    ),
    /unsupported system image/,
  );
  assert.equal(mutations, 0);
  await assert.rejects(
    runIsolated(
      { execute: true, sdkRoot: "/nonexistent-daykeeper-sdk" },
      forbidden,
      forbidden,
      forbidden,
    ),
    /required Android tool is missing/,
  );
  assert.equal(mutations, 0);
});
test("ownership guards reject AVD and emulator collisions", () => {
  assertOwnedAvd("Name: another\n", "daykeeper-isolated-new");
  assert.throws(() =>
    assertOwnedAvd("Name: daykeeper-isolated-old\n", "daykeeper-isolated-old"),
  );
  assertOwnedEmulator("emulator-5600", "owned", "owned\n");
  assert.throws(() =>
    assertOwnedEmulator("emulator-5600", "owned", "foreign\n"),
  );
});
test("cleanup refuses a missing or foreign AVD without delete", async () => {
  const calls = [];
  const run = async (...args) => {
    calls.push(args);
    return { stdout: "Name: foreign\nPath: /tmp/other/foreign.avd\n" };
  };
  await assert.rejects(
    cleanupOwnedAvd(
      {
        avdmanager: "avdmanager",
        name: "owned",
        avdHome: "/tmp/owned",
        created: true,
      },
      run,
    ),
  );
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0][1], ["list", "avd"]);
});

test("port selection skips occupied pairs and stays within supported range", async () => {
  const checked = [];
  assert.equal(
    await choosePort(async (port) => {
      checked.push(port);
      return port === 5558;
    }),
    5558,
  );
  assert.deepEqual(checked, [5554, 5556, 5558]);
  const exhausted = [];
  await assert.rejects(
    choosePort(async (port) => {
      exhausted.push(port);
      return false;
    }),
  );
  assert.equal(exhausted.at(-1), 5584);
});

test("cleanup discovery and deletion use the same isolated AVD home", async () => {
  const calls = [];
  await cleanupOwnedAvd(
    {
      avdmanager: "avdmanager",
      name: "owned",
      avdHome: "/tmp/isolated",
      created: true,
    },
    async (command, args, options) => {
      calls.push({ args, options });
      return { stdout: "Name: owned\nPath: /tmp/isolated/owned.avd\n" };
    },
  );
  assert.equal(calls.length, 2);
  for (const call of calls)
    assert.equal(call.options.env.ANDROID_AVD_HOME, "/tmp/isolated");
  assert.deepEqual(calls[1].args, ["delete", "avd", "--name", "owned"]);
});

test("startup failure can terminate only the spawned child without adb", async () => {
  const child = new EventEmitter();
  child.exitCode = null;
  child.signalCode = null;
  const signals = [];
  child.kill = (signal) => {
    signals.push(signal);
    queueMicrotask(() => child.emit("close"));
  };
  await terminateOwnChild(child, 10);
  assert.deepEqual(signals, ["SIGTERM"]);
});

test("owned child termination escalates and is bounded", async () => {
  const child = new EventEmitter();
  child.exitCode = null;
  child.signalCode = null;
  const signals = [];
  child.kill = (signal) => signals.push(signal);
  await assert.rejects(terminateOwnChild(child, 5), /retaining AVD/);
  assert.deepEqual(signals, ["SIGTERM", "SIGKILL"]);
});

for (const scenario of ["wrong-identity", "failed-tests"])
  test(`isolated orchestration: ${scenario}`, async () => {
    const root = await mkdtemp(join(tmpdir(), "daykeeper-harness-test-"));
    await Promise.all([
      mkdir(join(root, "cmdline-tools/latest/bin"), { recursive: true }),
      mkdir(join(root, "emulator"), { recursive: true }),
      mkdir(join(root, "platform-tools"), { recursive: true }),
      mkdir(join(root, "system-images/android-36/google_apis/arm64-v8a"), {
        recursive: true,
      }),
    ]);
    for (const path of [
      "cmdline-tools/latest/bin/avdmanager",
      "emulator/emulator",
      "platform-tools/adb",
    ])
      await writeFile(join(root, path), "");
    const calls = [];
    let ownedName = "";
    let ownedHome = "";
    let serial = "";
    const reports = [];
    const run = async (command, args) => {
      calls.push([command, args]);
      if (command === "df")
        return {
          stdout:
            "Filesystem 1024-blocks Used Available Capacity Mounted\n/dev/test 99999999 1 99999998 1% /\n",
        };
      if (args[0] === "list")
        return {
          stdout:
            calls.filter(([, a]) => a[0] === "list").length > 1
              ? `Name: ${ownedName}\nPath: ${ownedHome}/${ownedName}.avd\n`
              : "Name: daykeeper-isolated-placeholder\nPath: /tmp/other.avd\n",
        };
      if (args.includes("avd") && args.includes("name"))
        return {
          stdout:
            scenario === "wrong-identity" ? "foreign\n" : `${ownedName}\nOK\n`,
        };
      if (args.includes("sys.boot_completed")) return { stdout: "1\n" };
      if (args[0] === "devices")
        return { stdout: `List of devices attached\n${serial}\tdevice\n` };
      if (command === "./gradlew")
        throw new Error("synthetic instrumentation failure");
      if (args[0] === "wait-for-device") return { stdout: "" };
      return { stdout: "" };
    };
    const child = new EventEmitter();
    child.pid = 1234;
    child.exitCode = null;
    child.signalCode = null;
    child.kill = () => queueMicrotask(() => child.emit("close"));
    const create = async (_command, args, env) => {
      ownedName = args[args.indexOf("--name") + 1];
      ownedHome = env.ANDROID_AVD_HOME;
      return { stdout: "" };
    };
    const start = (_command, args) => {
      serial = `emulator-${args[args.indexOf("-port") + 1]}`;
      return child;
    };
    await assert.rejects(
      runIsolated(
        { execute: true, sdkRoot: root },
        run,
        start,
        create,
        async (name) => reports.push(name),
      ),
      scenario === "wrong-identity"
        ? /foreign AVD/
        : /synthetic instrumentation failure/,
    );
    assert.equal(
      calls.some(
        ([, args]) => args[0] === ":example:connectedDebugAndroidTest",
      ),
      scenario === "failed-tests",
    );
    assert.deepEqual(reports, scenario === "failed-tests" ? [ownedName] : []);
    assert(
      !calls.some(([, args]) => args.includes("emu") && args.includes("kill")),
    );
  });
