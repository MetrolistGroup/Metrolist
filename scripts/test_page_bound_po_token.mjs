import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { setImmediate } from "node:timers/promises";
import { test } from "node:test";
import { createContext, runInContext } from "node:vm";

const source = readFileSync(new URL(
    "../shared/src/commonMain/kotlin/com/metrolist/shared/youtube/playback/PageBoundPoTokenMinter.kt",
    import.meta.url,
), "utf8");
const generate = source.slice(source.indexOf("suspend fun generate("));
const script = generate.match(/"""([\s\S]*?)"""/)[1]
    .replace("${stringToU8(identifier)}", "[1]");

for (const outcome of ["success", "error"]) {
    for (const finishNewFirst of [false, true]) {
        test(`late token ${outcome} cannot overwrite a newer request (new first: ${finishNewFirst})`, async () => {
            const pending = [];
            const runtime = createContext({
                obtainPoToken: () => new Promise((resolve, reject) => pending.push({ resolve, reject })),
            });
            runInContext(script, runtime);
            runInContext(script, runtime);
            if (finishNewFirst) {
                pending[1].resolve([4, 5, 6]);
                await setImmediate();
            }
            if (outcome === "success") pending[0].resolve([1, 2, 3]);
            else pending[0].reject(new Error("cancelled request failed"));
            await setImmediate();
            assert.equal(runtime.__metrolistPoToken.state, finishNewFirst ? "ready" : "running");

            pending[1].resolve([4, 5, 6]);
            await setImmediate();
            assert.equal(runtime.__metrolistPoToken.state, "ready");
            assert.equal(runtime.__metrolistPoToken.value, "4,5,6");
        });
    }
}
