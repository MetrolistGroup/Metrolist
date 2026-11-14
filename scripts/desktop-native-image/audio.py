"""Private PipeWire server and clocked null sink for playback measurements."""

import contextlib
import json
import subprocess
import threading
import time


@contextlib.contextmanager
def private_audio(work, env):
    audio = work / "audio"
    runtime = audio / "runtime"
    runtime.mkdir(parents=True, mode=0o700)
    server_env = dict(env, XDG_RUNTIME_DIR=str(runtime), PIPEWIRE_RUNTIME_DIR=str(runtime), PIPEWIRE_REMOTE="pipewire-0")
    config = audio / "alsa.conf"
    config.write_text(
        '</usr/share/alsa/alsa.conf>\n'
        'pcm.!default { type pipewire server "pipewire-0" playback_node "r6-null"\n'
        '  hint { show on description "Private PipeWire output" } }\n'
    )
    stopped = threading.Event()
    linker = None
    with (audio / "server.log").open("w") as log, (audio / "links.log").open("w") as links:
        server = subprocess.Popen(["pipewire"], env=server_env, stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 15
            while not (runtime / "pipewire-0").exists():
                if server.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Private PipeWire did not start; see audio/server.log")
                time.sleep(0.1)
            subprocess.run([
                "pw-cli", "create-node", "adapter",
                "{ factory.name=support.null-audio-sink node.name=r6-null node.description=r6-null "
                "media.class=Audio/Sink object.linger=true audio.position=[FL FR] "
                "node.always-process=true adapter.auto-port-config={mode=dsp monitor=true position=preserve} }",
            ], env=server_env, check=True, stdout=log, stderr=log, timeout=10)

            def connect():
                connected = set()
                configured = set()
                while not stopped.is_set():
                    try:
                        graph = subprocess.run(["pw-dump"], env=server_env, capture_output=True,
                                               text=True, timeout=5, check=True)
                        for node in json.loads(graph.stdout):
                            info = node.get("info", {})
                            props = info.get("props", {})
                            serial = props.get("object.serial")
                            if not node.get("type", "").endswith(":Node") or props.get("media.class") != "Stream/Output/Audio" or serial in configured:
                                continue
                            formats = info.get("params", {}).get("EnumFormat", [])
                            if not formats:
                                continue
                            # A private server has no session manager to configure stream ports.
                            parameters = {"direction": "Output", "mode": "dsp", "format": formats[0]}
                            subprocess.run(["pw-cli", "set-param", str(node["id"]), "PortConfig", json.dumps(parameters)],
                                           env=server_env, check=True, stdout=links, stderr=links, timeout=5)
                            configured.add(serial)
                        result = subprocess.run(["pw-link", "-o"], env=server_env, capture_output=True,
                                                text=True, timeout=5, check=True)
                        outputs = [line.strip() for line in result.stdout.splitlines() if ":" in line and not line.strip().startswith("r6-null:")]
                        connected.intersection_update(outputs)
                        for output in outputs:
                            if output in connected:
                                continue
                            channel = "FR" if output.endswith(("_FR", "_1")) else "FL"
                            target = f"r6-null:playback_{channel}"
                            linked = subprocess.run(["pw-link", output, target], env=server_env,
                                                    stdout=links, stderr=links, timeout=5)
                            if linked.returncode == 0:
                                links.write(f"{output} -> {target}\n")
                                links.flush()
                                connected.add(output)
                    except (subprocess.SubprocessError, OSError, ValueError) as error:
                        links.write(f"{type(error).__name__}: {error}\n")
                        links.flush()
                    stopped.wait(0.2)

            linker = threading.Thread(target=connect, name="private-audio-links", daemon=True)
            linker.start()
            yield dict(env, PIPEWIRE_RUNTIME_DIR=str(runtime), PIPEWIRE_REMOTE="pipewire-0", ALSA_CONFIG_PATH=str(config))
        finally:
            stopped.set()
            if linker is not None:
                linker.join(timeout=6)
            server.terminate()
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
