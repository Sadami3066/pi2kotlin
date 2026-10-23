#!/usr/bin/env node
import { execSync, spawnSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const version = process.argv[2] ?? "0.1.3";
const m2 = join(homedir(), ".m2", "repository", "dev", "pi");
const modules = ["pi-ai", "pi-agent", "pi-coding-agent"];
const expectedClassMajor = 61;

let failures = 0;
const rows = [];

function check(name, ok, detail) {
  rows.push({ name, ok, detail: detail ?? "" });
  if (!ok) failures += 1;
}

function artifactPath(module, suffix, ext, classifier) {
  const dir = join(m2, `${module}-${suffix}`, version);
  const suffixPart = classifier ? `-${classifier}` : "";
  return join(dir, `${module}-${suffix}-${version}${suffixPart}.${ext}`);
}

function unzipList(zipFile) {
  const result = spawnSync("unzip", ["-Z1", zipFile], { encoding: "utf8" });
  return result.status === 0 ? result.stdout.trim().split("\n") : [];
}

function unzipBytes(zipFile, entry) {
  const result = spawnSync("unzip", ["-p", zipFile, entry], { encoding: "buffer" });
  return result.status === 0 ? result.stdout : null;
}

for (const module of modules) {
  const jar = artifactPath(module, "jvm", "jar");
  const sources = artifactPath(module, "jvm", "jar", "sources");
  const pom = artifactPath(module, "jvm", "pom");
  const aar = artifactPath(module, "android", "aar");
  const androidSources = artifactPath(module, "android", "jar", "sources");
  const moduleFile = artifactPath(module, "jvm", "module");
  check(`${module}: jvm jar`, existsSync(jar), jar);
  check(`${module}: jvm pom`, existsSync(pom), pom);
  check(`${module}: gradle module metadata`, existsSync(moduleFile), moduleFile);
  check(`${module}: android aar`, existsSync(aar), aar);
  check(`${module}: jvm sources jar`, existsSync(sources), sources);
  check(`${module}: android sources jar`, existsSync(androidSources), androidSources);

  if (existsSync(jar)) {
    const entries = unzipList(jar).filter((entry) => entry.endsWith(".class"));
    check(`${module}: jar has classes`, entries.length > 0, `${entries.length} classes`);
    const sample = entries.length > 400 ? entries.filter((_, index) => index % 7 === 0) : entries;
    const violations = [];
    for (const entry of sample) {
      const bytes = unzipBytes(jar, entry);
      const major = bytes ? (bytes[6] << 8) | bytes[7] : -1;
      if (major !== expectedClassMajor) violations.push(`${entry}=${major}`);
    }
    check(
      `${module}: all sampled classes major ${expectedClassMajor} (Java 17)`,
      violations.length === 0,
      violations.length === 0 ? `${sample.length} classes` : violations.slice(0, 5).join(", "),
    );
  }

  if (existsSync(pom)) {
    const text = readFileSync(pom, "utf8");
    check(`${module}: pom version`, text.includes(`<version>${version}</version>`), "");
    if (module !== "pi-ai") {
      const dependencyModule = module === "pi-agent" ? "pi-ai-jvm" : "pi-agent-jvm";
      check(`${module}: pom depends on ${dependencyModule}`, text.includes(dependencyModule), "");
    }
  }

  if (existsSync(aar)) {
    const entries = unzipList(aar);
    check(`${module}: aar has classes.jar`, entries.includes("classes.jar"), "");
    check(`${module}: aar has AndroidManifest.xml`, entries.includes("AndroidManifest.xml"), "");
  }
}

const width = Math.max(...rows.map((row) => row.name.length)) + 2;
for (const row of rows) {
  const status = row.ok ? "PASS" : "FAIL";
  console.log(`${status}  ${row.name.padEnd(width)} ${row.detail}`);
}
console.log(`\n${failures === 0 ? "PASS" : "FAIL"}: ${rows.length - failures}/${rows.length} checks passed for ${version}`);
process.exit(failures === 0 ? 0 : 1);
