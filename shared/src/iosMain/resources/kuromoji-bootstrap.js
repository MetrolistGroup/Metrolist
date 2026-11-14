(function (global) {
    "use strict";

    var dictionaryLimits = Object.assign(Object.create(null), {
        "base.dat.gz": 4000000,
        "cc.dat.gz": 1800000,
        "check.dat.gz": 3200000,
        "tid.dat.gz": 1700000,
        "tid_map.dat.gz": 1600000,
        "tid_pos.dat.gz": 6200000,
        "unk_char.dat.gz": 1000,
        "unk_compat.dat.gz": 1000,
        "unk.dat.gz": 12000,
        "unk_invoke.dat.gz": 2000,
        "unk_map.dat.gz": 2000,
        "unk_pos.dat.gz": 12000
    });
    var dictionaryData = Object.create(null);
    var totalDataLength = 0;
    var timerQueue = [];
    var nextTimerId = 0;
    var tokenizer = null;
    var buildError = null;

    global.setTimeout = function (callback) {
        if (typeof callback !== "function") throw new TypeError("Timer callback must be a function");
        var timer = { id: ++nextTimerId, callback: callback, cancelled: false };
        timerQueue.push(timer);
        return timer.id;
    };
    global.clearTimeout = function (id) {
        timerQueue.forEach(function (timer) {
            if (timer.id === id) timer.cancelled = true;
        });
    };
    global.__metrolistDrainTimers = function () {
        var count = 0;
        while (timerQueue.length) {
            if (++count > 100000) throw new Error("Kuromoji timer queue limit exceeded");
            var timer = timerQueue.shift();
            if (!timer.cancelled) timer.callback();
        }
    };

    global.__metrolistAddDictionary = function (name, base64) {
        var byteLimit = dictionaryLimits[name];
        if (!byteLimit || Object.prototype.hasOwnProperty.call(dictionaryData, name)) {
            throw new Error("Unrecognized or duplicate Kuromoji dictionary resource");
        }
        if (typeof base64 !== "string" || base64.length === 0 || base64.length > Math.ceil(byteLimit / 3) * 4 ||
            base64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) {
            throw new Error("Invalid or oversized Kuromoji dictionary resource");
        }
        var decodedLength = Math.floor(base64.length * 3 / 4) - (base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0);
        if (decodedLength > byteLimit) throw new Error("Invalid or oversized Kuromoji dictionary resource");
        totalDataLength += base64.length;
        if (totalDataLength > 23 * 1024 * 1024) throw new Error("Kuromoji dictionary size limit exceeded");
        dictionaryData[name] = base64;
    };

    function decodeBase64(value) {
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        var length = value.length;
        var output = new Uint8Array(Math.floor(length * 3 / 4) - (value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0));
        var outputIndex = 0;
        for (var i = 0; i < length; i += 4) {
            var chunk = (alphabet.indexOf(value[i]) << 18) |
                (alphabet.indexOf(value[i + 1]) << 12) |
                ((alphabet.indexOf(value[i + 2]) & 63) << 6) |
                (alphabet.indexOf(value[i + 3]) & 63);
            output[outputIndex++] = (chunk >> 16) & 255;
            if (value[i + 2] !== "=") output[outputIndex++] = (chunk >> 8) & 255;
            if (value[i + 3] !== "=") output[outputIndex++] = chunk & 255;
        }
        return output.buffer;
    }

    function LocalDictionaryRequest() {
        this.method = null;
        this.url = null;
        this.responseType = "";
        this.response = null;
        this.status = 0;
        this.statusText = "";
        this.onload = null;
        this.onerror = null;
    }

    LocalDictionaryRequest.prototype.open = function (method, url) {
        this.method = method;
        this.url = url;
    };
    LocalDictionaryRequest.prototype.send = function () {
        var prefix = "metrolist-dictionary/";
        var name = typeof this.url === "string" && this.url.indexOf(prefix) === 0 ? this.url.slice(prefix.length) : "";
        var base64 = dictionaryLimits[name] ? dictionaryData[name] : null;
        if (this.method !== "GET" || this.responseType !== "arraybuffer" || !base64) {
            this.statusText = "Dictionary resource unavailable";
            if (this.onerror) this.onerror(new Error(this.statusText));
            return;
        }
        this.status = 200;
        this.statusText = "OK";
        this.response = decodeBase64(base64);
        if (this.onload) this.onload.call(this);
    };
    global.XMLHttpRequest = LocalDictionaryRequest;

    global.__metrolistStartBuild = function () {
        buildError = null;
        tokenizer = null;
        global.kuromoji.builder({ dicPath: "metrolist-dictionary/" }).build(function (error, result) {
            buildError = error || null;
            tokenizer = result || null;
        });
    };
    global.__metrolistBuildError = function () {
        return buildError ? String(buildError) : null;
    };
    global.__metrolistIsBuilt = function () {
        return tokenizer !== null && buildError === null;
    };
    global.__metrolistTokenize = function (text) {
        if (!tokenizer) throw new Error("Kuromoji tokenizer is not initialized");
        return JSON.stringify(tokenizer.tokenize(text).map(function (token) {
            return [token.surface_form, token.pronunciation, token.reading];
        }));
    };
    global.__metrolistReleaseDictionaryData = function () {
        dictionaryData = Object.create(null);
        totalDataLength = 0;
        timerQueue = [];
    };
})(this);
