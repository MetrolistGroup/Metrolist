package com.metrolist.shared.youtube.playback

interface PageBoundPoTokenJavaScriptRuntime {
    val available: Boolean

    suspend fun evaluate(script: String): String

    suspend fun reset()
}

const val PAGE_BOUND_TOKEN_ORIGIN_URL = "https://www.youtube.com/robots.txt"

const val PAGE_BOUND_TOKEN_SCRIPT =
    """
    var bgVmFunctions = null;
    var poTokenMinter = null;
    function loadBotGuard(challengeData) {
      var bgVm = window[challengeData.globalName];
      bgVmFunctions = null;
      if (!bgVm || !bgVm.a) throw new Error("BotGuard VM is unavailable");
      var vmLoad = bgVm.a(challengeData.program, function(asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
        bgVmFunctions = { asyncSnapshotFunction: asyncSnapshotFunction, shutdownFunction: shutdownFunction,
          passEventFunction: passEventFunction, checkCameraFunction: checkCameraFunction };
      }, true, undefined, function() {}, [[], []], undefined, false,
        [function() {}, function() {}, function() {}, function() {}, function() {}]);
      return Promise.resolve(vmLoad).then(function() {
        return new Promise(function(resolve, reject) {
          var attempts = 0;
          var interval = setInterval(function() {
            if (bgVmFunctions && bgVmFunctions.asyncSnapshotFunction) { clearInterval(interval); resolve(bgVmFunctions); }
            else if (++attempts >= 10000) { clearInterval(interval); reject(new Error("Timed out waiting for BotGuard snapshot")); }
          }, 1);
        });
      });
    }
    function snapshot(vmFunctions, webPoSignalOutput) {
      return new Promise(function(resolve, reject) {
        try { vmFunctions.asyncSnapshotFunction(function(response) { resolve(response); },
          [undefined, undefined, webPoSignalOutput, undefined]); } catch (error) { reject(error); }
      });
    }
    function runBotGuard(challengeData) {
      var interpreter = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
      if (!interpreter) throw new Error("BotGuard interpreter is unavailable");
      new Function(interpreter)();
      var webPoSignalOutput = [];
      return loadBotGuard({ globalName: challengeData.globalName, program: challengeData.program })
        .then(function(vmFunctions) { return snapshot(vmFunctions, webPoSignalOutput); })
        .then(function(response) { return { webPoSignalOutput: webPoSignalOutput, botguardResponse: response }; });
    }
    async function createPoTokenMinter(webPoSignalOutput, integrityToken) {
      var getMinter = webPoSignalOutput[0];
      if (typeof getMinter !== "function") throw new Error("BotGuard minter factory is unavailable");
      poTokenMinter = await getMinter(integrityToken);
      if (typeof poTokenMinter !== "function") throw new Error("BotGuard minter is unavailable");
    }
    async function obtainPoToken(identifier) {
      if (!poTokenMinter) throw new Error("BotGuard minter has not been initialized");
      var result = await poTokenMinter(identifier);
      if (!(result instanceof Uint8Array)) throw new Error("BotGuard returned an invalid token");
      return result;
    }
    """
