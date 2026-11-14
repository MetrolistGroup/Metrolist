using MetroWv2Helper;

static void Check(bool value)
{
    if (!value) throw new Exception("Login completion assertion failed");
}

var cookies = new Dictionary<string, string> { ["SAPISID"] = "session" };
var complete = """{"visitorData":"visitor","dataSyncId":"channel||account","authUser":"3"}""";
foreach (var snapshot in new[] { "null", "{}", "{broken", "[]",
    """{"visitorData":"visitor","dataSyncId":"channel"}""",
    """{"visitorData":"visitor","dataSyncId":"channel","authUser":"-1"}""" })
{
    Check(LoginCompletion.ReadYouTubeCredentials(cookies, snapshot) == null);
}
Check(LoginCompletion.ReadYouTubeCredentials(new Dictionary<string, string>(), complete) == null);
var payload = LoginCompletion.ReadYouTubeCredentials(cookies, complete)!;
Check(payload.authUser == "3" && payload.dataSyncId == "channel" && payload.visitorData == "visitor");
Check(LoginCompletion.ReadYouTubeCredentials(cookies, complete.Replace("channel||account", "||account"))!.dataSyncId == "account");

// Configuration may become available without another navigation.
var late = new LoginCompletion();
var writes = 0;
foreach (var snapshot in new[] { "{}", complete, complete })
{
    var candidate = LoginCompletion.ReadYouTubeCredentials(cookies, snapshot);
    if (candidate != null) late.TryWrite(candidate, _ => writes++);
}
Check(writes == 1 && late.IsCompleted);

var completion = new LoginCompletion();
using var entered = new ManualResetEventSlim();
using var finish = new ManualResetEventSlim();
var first = Task.Run(() => completion.TryWrite(payload, _ => { entered.Set(); finish.Wait(); }));
Check(entered.Wait(TimeSpan.FromSeconds(5)));
try
{
    Check(!completion.TryWrite(payload, _ => throw new Exception("Duplicate write")));
}
finally { finish.Set(); }
Check(await first);
Check(!completion.TryWrite(payload, _ => throw new Exception("Duplicate completion")));

var retry = new LoginCompletion();
try { retry.TryWrite(payload, _ => throw new IOException("Output unavailable")); }
catch (IOException) { }
Check(!retry.IsCompleted);
Check(retry.TryWrite(payload, _ => { }));
Console.WriteLine("Login completion tests passed");
