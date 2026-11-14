import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import vm from "node:vm";

const commit = "d0357c1427acb912815fc8ab59f284dd08bad0fa";
const assetRoot = new URL("../shared/src/iosMain/resources/", import.meta.url);
const dictionaryFiles = [
    ["base.dat.gz", "0803327762e1c93ca731e4319ab8343340f2806bb84941207782cde9d2d5a8eb", 4_000_000],
    ["cc.dat.gz", "02b7631be0d4de3a1a75cd9f9cc51536e4f94c9e6b389b813e06ba0f6e7de765", 1_800_000],
    ["check.dat.gz", "193ae0035fff6fe812b58d9ee730e7a7d7ee601d918481ce51075c58114f6cc9", 3_200_000],
    ["tid.dat.gz", "d43d831cb6fb0f0a411739cd287a6d5e998e121a8daca614df14a81a0dcac586", 1_700_000],
    ["tid_map.dat.gz", "33efd5ffd87a70f669add093fa39dee44341d58f940844ef107c8fd98bb795b2", 1_600_000],
    ["tid_pos.dat.gz", "60dbfc99a6ab993f30c5dab648bec6ad7f9aaefa5c14e1843837d95e509f8895", 6_200_000],
    ["unk_char.dat.gz", "9a8e86fd9aff32d323fbb59f5a7006f05927a11f8173c90712cc56293aeb3225", 1_000],
    ["unk_compat.dat.gz", "50f60aa29bc2e86c2903ab8c825bb6fa604d2b294d96941c1d3924259791899d", 1_000],
    ["unk.dat.gz", "f7f991cdeb9bfd3e9c0e4577cc50ee0815a11c508cccd444a9d3ab3c81521100", 12_000],
    ["unk_invoke.dat.gz", "6b210889548457c3006913afd12c8b525562255f2709e404604be9614a25e94c", 2_000],
    ["unk_map.dat.gz", "6df12460e5477230bb6fd9641def918b699fc0a8868016b6c9f794488630509b", 2_000],
    ["unk_pos.dat.gz", "5b183a29f281acc7e0542beca47b83f7985047c0a2d27e78a66f32276be5ad11", 12_000],
];

function validate(spec, bytes) {
    assert.ok(bytes?.length > 0, `${spec[0]} is missing or empty`);
    assert.ok(bytes.length <= spec[2], `${spec[0]} exceeds its bound`);
    assert.equal(createHash("sha256").update(bytes).digest("hex"), spec[1], `${spec[0]} checksum mismatch`);
}

async function download(spec, fetchResource = fetch) {
    const response = await fetchResource(`https://raw.githubusercontent.com/takuyaa/kuromoji.js/${commit}/dict/${spec[0]}`, {
        redirect: "manual",
        signal: AbortSignal.timeout(30_000),
    });
    assert.equal(response.status, 200, `Unexpected status for ${spec[0]}`);
    const declaredLength = Number(response.headers.get("content-length"));
    assert.ok(!Number.isFinite(declaredLength) || declaredLength <= spec[2], `${spec[0]} exceeds its bound`);
    const chunks = [];
    let size = 0;
    for await (const chunk of response.body) {
        size += chunk.length;
        assert.ok(size <= spec[2], `${spec[0]} exceeds its bound`);
        chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);
    validate(spec, bytes);
    return bytes;
}

const dictionary = new Map();
for (const spec of dictionaryFiles) dictionary.set(spec[0], await download(spec));
assert.throws(() => validate(dictionaryFiles[0], undefined), /missing or empty/);
assert.throws(() => validate(dictionaryFiles[0], dictionary.get("base.dat.gz").subarray(0, -1)), /checksum mismatch/);
assert.throws(() => validate(dictionaryFiles[0], Buffer.alloc(dictionaryFiles[0][2] + 1)), /exceeds its bound/);
await assert.rejects(() => download(dictionaryFiles[0].map((value, index) => index === 0 ? "absent.dat.gz" : value)), /Unexpected status/);
await assert.rejects(() => download(dictionaryFiles[0], async () => { throw new Error("offline"); }), /offline/);

const bootstrap = await readFile(new URL("kuromoji-bootstrap.js", assetRoot), "utf8");
const engine = await readFile(new URL("kuromoji.js", assetRoot), "utf8");
assert.equal(createHash("sha256").update(engine).digest("hex"), "ab0ac10f1c4a9b5e246e63e57b04ba14d3a6148cd7e8d62cb97a960d4f85a3e6");
assert.equal(createHash("sha256").update(bootstrap).digest("hex"), "3a2f75400ef6f04301408701c2a2dbe23e614561df4aea3ab36504e9cca4ceb9");
const boundsSandbox = vm.createContext({});
vm.runInContext(bootstrap, boundsSandbox);
assert.throws(() => dictionaryFiles.forEach(([name, , maxBytes]) => {
    boundsSandbox.__metrolistAddDictionary(name, "AAAA".repeat(Math.floor(maxBytes / 3)));
}), /size limit/);

const sandbox = vm.createContext({});
assert.equal("require" in sandbox, false);
assert.equal("window" in sandbox, false);
assert.equal("document" in sandbox, false);
vm.runInContext(bootstrap, sandbox);
vm.runInContext(engine, sandbox);
assert.throws(() => sandbox.__metrolistAddDictionary("../base.dat.gz", "AAAA"), /Unrecognized/);
assert.throws(() => sandbox.__metrolistAddDictionary("toString", "AAAA"), /Unrecognized/);
assert.throws(() => sandbox.__metrolistAddDictionary("base.dat.gz", ""), /Invalid or oversized/);
assert.throws(() => sandbox.__metrolistAddDictionary("base.dat.gz", "A".repeat(5_333_340)), /Invalid or oversized/);
let missingResourceError;
const missingRequest = new sandbox.XMLHttpRequest();
missingRequest.open("GET", "metrolist-dictionary/base.dat.gz", true);
missingRequest.responseType = "arraybuffer";
missingRequest.onerror = error => { missingResourceError = error; };
missingRequest.send();
assert.ok(missingResourceError);
let externalRequestError;
const externalRequest = new sandbox.XMLHttpRequest();
externalRequest.open("GET", "https://example.invalid/base.dat.gz", true);
externalRequest.responseType = "arraybuffer";
externalRequest.onerror = error => { externalRequestError = error; };
externalRequest.send();
assert.ok(externalRequestError);

for (const [name, bytes] of dictionary) sandbox.__metrolistAddDictionary(name, bytes.toString("base64"));
sandbox.__metrolistStartBuild();
sandbox.__metrolistDrainTimers();
assert.equal(sandbox.__metrolistBuildError(), null);
assert.equal(sandbox.__metrolistIsBuilt(), true);

function readings(text) {
    return JSON.parse(sandbox.__metrolistTokenize(text)).map(([surface, pronunciation, reading]) => [
        surface,
        pronunciation && pronunciation !== "*" ? pronunciation : reading,
    ]);
}

assert.deepEqual(readings("東京"), [["東京", "トーキョー"]]);
assert.deepEqual(readings("日本語"), [["日本語", "ニホンゴ"]]);
assert.deepEqual(readings("今日は東京です"), [["今日", "キョー"], ["は", "ワ"], ["東京", "トーキョー"], ["です", "デス"]]);
assert.deepEqual(readings("東京かな"), [["東京", "トーキョー"], ["か", "カ"], ["な", "ナ"]]);
assert.deepEqual(readings("かなカタカナ"), [["かな", "カナ"], ["カタカナ", "カタカナ"]]);
sandbox.__metrolistReleaseDictionaryData();
assert.deepEqual(readings("東京"), [["東京", "トーキョー"]]);
let releasedDataError;
const releasedRequest = new sandbox.XMLHttpRequest();
releasedRequest.open("GET", "metrolist-dictionary/base.dat.gz", true);
releasedRequest.responseType = "arraybuffer";
releasedRequest.onerror = error => { releasedDataError = error; };
releasedRequest.send();
assert.ok(releasedDataError);
for (const file of ["kuromoji-LICENSE-2.0.txt", "kuromoji-NOTICE.md"]) {
    assert.ok((await readFile(new URL(file, assetRoot))).length > 0);
}
console.log("Verified isolated full-IPADIC Kuromoji.js runtime and iOS dictionary bridge");
