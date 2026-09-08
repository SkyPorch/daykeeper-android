#!/usr/bin/env node
import assert from "node:assert/strict";
import { execFile, spawn } from "node:child_process";
import {
  access,
  cp,
  mkdir,
  mkdtemp,
  rm,
  lstat,
  rename,
} from "node:fs/promises";
import { realpathSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname, basename, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";
import { createServer } from "node:net";

const exec = promisify(execFile);
const IMAGE = "system-images;android-36;google_apis;arm64-v8a";
const MIN_FREE_BYTES = 8 * 1024 ** 3;
const exists = (p) =>
  access(p).then(
    () => true,
    () => false,
  );
const quote = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
export function parseArgs(argv) {
  const index = argv.indexOf("--image");
  return {
    execute: argv.includes("--execute"),
    sdkRoot: process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || "",
    image: index >= 0 ? argv[index + 1] : IMAGE,
  };
}
export async function choosePort(probe = probePair) {
  // Installed emulator -help-port specifies 5554..5584; each consumes two ports.
  for (let port = 5554; port <= 5584; port += 2)
    if (await probe(port)) return port;
  throw new Error("No unused emulator console/ADB port pair is available");
}
async function probePair(port) {
  const servers = [];
  try {
    for (const candidate of [port, port + 1]) {
      const server = createServer();
      servers.push(server);
      await new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(
          { host: "127.0.0.1", port: candidate, exclusive: true },
          resolve,
        );
      });
    }
    return true;
  } catch (error) {
    if (error.code === "EADDRINUSE") return false;
    throw error;
  } finally {
    await Promise.all(
      servers
        .filter((server) => server.listening)
        .map((server) => new Promise((resolve) => server.close(resolve))),
    );
  }
}
export async function terminateOwnChild(child, graceMs = 5000) {
  if (!child?.pid || child.exitCode !== null || child.signalCode !== null)
    return;
  let timer;
  const stopped = new Promise((resolve, reject) => {
    child.once("close", resolve);
    timer = setTimeout(() => {
      child.kill("SIGKILL");
      timer = setTimeout(
        () => reject(new Error("Owned emulator did not exit; retaining AVD")),
        graceMs,
      );
    }, graceMs);
  });
  try {
    child.kill("SIGTERM");
    await stopped;
  } finally {
    clearTimeout(timer);
  }
}
function createAvd(command, args, env, signal) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["pipe", "pipe", "inherit"],
      env,
      signal,
    });
    let stdout = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    let processError;
    child.once("error", (error) => {
      processError = error;
    });
    child.once("close", (code) =>
      code === 0 && !processError
        ? resolve({ stdout })
        : reject(processError || new Error(`avdmanager create exited ${code}`)),
    );
    child.stdin.end("no\n");
  });
}

export function assertExactSerial(devices, serial) {
  const row = devices
    .split(/\r?\n/)
    .find(
      (line) => line.startsWith(`${serial}\t`) || line.startsWith(`${serial} `),
    );
  assert(
    row?.endsWith("\tdevice") || row?.endsWith(" device"),
    `device ${serial} is not the exact ready emulator`,
  );
}
export function assertOwnedAvd(output, name) {
  assert(
    !new RegExp(`^\\s*Name:\\s*${quote(name)}\\s*$`, "m").test(output),
    `refusing to reuse existing AVD ${name}`,
  );
}
export function assertOwnedEmulator(serial, expected, actual) {
  assert.equal(
    actual.trim(),
    expected,
    `serial ${serial} belongs to a foreign AVD`,
  );
}

export async function preflight({ sdkRoot, image = IMAGE }, run = exec) {
  assert(sdkRoot, "ANDROID_SDK_ROOT or ANDROID_HOME is required");
  assert(
    /^system-images;android-(35|36);google_apis(?:_playstore)?;(?:arm64-v8a|x86_64)$/.test(
      image,
    ),
    `unsupported system image: ${image}`,
  );
  const tools = {
    avdmanager: `${sdkRoot}/cmdline-tools/latest/bin/avdmanager`,
    emulator: `${sdkRoot}/emulator/emulator`,
    adb: `${sdkRoot}/platform-tools/adb`,
  };
  for (const path of Object.values(tools))
    assert(await exists(path), `required Android tool is missing: ${path}`);
  const imagePath = `${sdkRoot}/${image.replace(/^system-images[\\/]/, "system-images/").replaceAll(";", "/")}`;
  assert(await exists(imagePath), `required system image is missing: ${image}`);
  for (const storage of new Set([sdkRoot, tmpdir(), process.cwd()])) {
    const { stdout: df } = await run("df", ["-Pk", storage]);
    const free =
      Number(df.trim().split(/\r?\n/).at(-1)?.trim().split(/\s+/)[3]) * 1024;
    assert(
      Number.isFinite(free) && free >= MIN_FREE_BYTES,
      `at least ${MIN_FREE_BYTES} bytes free is required on ${storage} (found ${free})`,
    );
  }
  const { stdout: avds } = await run(tools.avdmanager, ["list", "avd"]);
  const name = `daykeeper-isolated-${Date.now()}-${process.pid}`;
  assertOwnedAvd(avds, name);
  const port = await choosePort();
  return { sdkRoot, image, name, port, serial: `emulator-${port}`, tools };
}

export async function cleanupOwnedAvd(
  { avdmanager, name, avdHome, env, created, removeHome = false },
  run = exec,
) {
  if (!created && !removeHome) return;
  const ownedEnv = env || { ...process.env, ANDROID_AVD_HOME: avdHome };
  assert.equal(
    ownedEnv.ANDROID_AVD_HOME,
    avdHome,
    "cleanup environment must match owned AVD home",
  );
  const { stdout } = await run(avdmanager, ["list", "avd"], { env: ownedEnv });
  const present = new RegExp(`^\\s*Name:\\s*${quote(name)}\\s*$`, "m").test(
    stdout,
  );
  if (present) {
    assert(
      stdout.includes(`${avdHome}/${name}.avd`),
      "refusing cleanup of an AVD outside the owned home",
    );
    await run(avdmanager, ["delete", "avd", "--name", name], { env: ownedEnv });
  } else assert(!created, "refusing cleanup of an unrecognized AVD");
  if (removeHome) {
    assert.equal(
      dirname(avdHome),
      resolve(tmpdir()),
      "cleanup must target the isolated temporary parent",
    );
    assert(
      /^daykeeper-avd-[A-Za-z0-9]+$/.test(basename(avdHome)),
      "cleanup must target a generated AVD home",
    );
    const info = await lstat(avdHome);
    assert(
      info.isDirectory() && !info.isSymbolicLink(),
      "cleanup home must be the owned directory",
    );
    await rm(avdHome, { recursive: true, force: true });
  }
}

export async function prepareReports(name, root = process.cwd()) {
  const destination = join(root, "TestResults/android", name);
  await mkdir(destination, { recursive: true });
  const previous = join(root, "example/build/reports/androidTests/connected");
  if (await exists(previous))
    await rename(previous, join(destination, "previous-run-not-evidence"));
}

export async function collectReports(name, root = process.cwd()) {
  const destination = join(root, "TestResults/android", name, "current-run");
  await mkdir(destination, { recursive: true });
  const source = join(root, "example/build/reports/androidTests/connected");
  if (await exists(source))
    await cp(source, destination, {
      recursive: true,
    });
}

export async function runIsolated(
  options,
  run = exec,
  spawnProcess = spawn,
  create = createAvd,
  collect = collectReports,
  prepare = prepareReports,
) {
  const cleanupRun = run;
  const commandRun = run;
  run = (command, args, settings = {}) =>
    commandRun(command, args, {
      timeout: 120_000,
      ...settings,
      signal: options.signal,
    });
  const plan = await preflight(options, run);
  if (!options.execute) {
    console.log(JSON.stringify({ mode: "dry-run", ...plan }));
    return plan;
  }
  let child;
  let created = false;
  let avdHome;
  let testStarted = false;
  let primaryError;
  try {
    avdHome = await mkdtemp(join(tmpdir(), "daykeeper-avd-"));
    const { stdout: avdDf } = await run("df", ["-Pk", avdHome]);
    const avdFree =
      Number(avdDf.trim().split(/\r?\n/).at(-1)?.trim().split(/\s+/)[3]) * 1024;
    assert(
      Number.isFinite(avdFree) && avdFree >= MIN_FREE_BYTES,
      `at least ${MIN_FREE_BYTES} bytes free is required for the isolated AVD (found ${avdFree})`,
    );
    const ownedEnv = { ...process.env, ANDROID_AVD_HOME: avdHome };
    await create(
      plan.tools.avdmanager,
      [
        "create",
        "avd",
        "--name",
        plan.name,
        "--package",
        plan.image,
        "--device",
        "pixel_6",
      ],
      ownedEnv,
      options.signal,
    );
    created = true;
    child = spawnProcess(
      plan.tools.emulator,
      [
        "-avd",
        plan.name,
        "-port",
        String(plan.port),
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
        "-no-snapshot",
        "-wipe-data",
      ],
      { stdio: ["ignore", "inherit", "inherit"], detached: true, env: ownedEnv },
    );
    assert(child, "emulator spawn returned no process");
    let spawnError;
    child.on("error", (error) => {
      spawnError = error;
    });
    if (!child.pid) {
      // A failed asynchronous spawn emits error, then close. Observe both before cleanup.
      await new Promise((resolve) => child.once("close", resolve));
      throw spawnError || new Error("emulator process did not start");
    }
    await run(plan.tools.adb, ["-s", plan.serial, "wait-for-device"], {
      timeout: 120000,
    });
    const { stdout: actualName } = await run(plan.tools.adb, [
      "-s",
      plan.serial,
      "emu",
      "avd",
      "name",
    ]);
    assertOwnedEmulator(plan.serial, plan.name, actualName.split(/\r?\n/)[0]);
    for (let i = 0; i < 120; i++) {
      const { stdout } = await run(plan.tools.adb, [
        "-s",
        plan.serial,
        "shell",
        "getprop",
        "sys.boot_completed",
      ]);
      if (stdout.trim() === "1") break;
      if (i === 119) throw new Error("owned emulator did not finish booting");
      await new Promise((r) => setTimeout(r, 1000));
    }
    const { stdout: devices } = await run(plan.tools.adb, ["devices"]);
    assertExactSerial(devices, plan.serial);
    if (spawnError) throw spawnError;
    await prepare(plan.name);
    testStarted = true;
    await run(
      "./gradlew",
      [
        ":example:connectedDebugAndroidTest",
        "--no-daemon",
        "--console=plain",
        "--serial",
        plan.serial,
      ],
      {
        env: { ...process.env, ANDROID_SERIAL: plan.serial },
        timeout: 1_200_000,
        maxBuffer: 16 * 1024 * 1024,
      },
    );
  } catch (error) {
    primaryError = error;
    throw error;
  } finally {
    let reportError;
    if (testStarted) {
      try {
        await collect(plan.name);
      } catch (error) {
        reportError = error;
      }
    }
    // Never kill by serial: a serial may have been reused by another emulator.
    await terminateOwnChild(child);
    await cleanupOwnedAvd(
      {
        avdmanager: plan.tools.avdmanager,
        name: plan.name,
        avdHome,
        env: avdHome
          ? { ...process.env, ANDROID_AVD_HOME: avdHome }
          : undefined,
        created,
        removeHome: Boolean(avdHome),
      },
      cleanupRun,
    );
    if (reportError) {
      if (!primaryError) throw reportError;
      console.error(
        "Report collection also failed; original instrumentation error retained.",
      );
    }
  }
}

export async function withTerminationSignals(action, host = process) {
  const controller = new AbortController();
  const handlers = new Map(
    ["SIGINT", "SIGTERM"].map((name) => [
      name,
      () => {
        if (!controller.signal.aborted)
          controller.abort(new Error(`Interrupted by ${name}`));
      },
    ]),
  );
  for (const [name, handler] of handlers) host.on(name, handler);
  try {
    return await action(controller.signal);
  } finally {
    for (const [name, handler] of handlers) host.removeListener(name, handler);
  }
}

const invokedDirectly =
  process.argv[1] &&
  (import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href ||
    realpathSync(fileURLToPath(import.meta.url)) ===
      realpathSync(process.argv[1]));
if (invokedDirectly)
  withTerminationSignals((signal) =>
    runIsolated({ ...parseArgs(process.argv.slice(2)), signal }),
  ).catch((e) => {
    console.error(e.message);
    process.exitCode = 1;
  });
