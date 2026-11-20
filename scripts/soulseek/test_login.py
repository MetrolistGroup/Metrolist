import io
import json
import sys
import tempfile
from pathlib import Path

import keyring
from aioslsk.exceptions import AuthenticationError
from keyring.backend import KeyringBackend

import metrolist_slsk as slsk


class MemoryKeyring(KeyringBackend):
    priority = 1

    def __init__(self):
        self.items = {}

    def get_password(self, service, username):
        return self.items.get((service, username))

    def set_password(self, service, username, password):
        self.items[(service, username)] = password

    def delete_password(self, service, username):
        self.items.pop((service, username))


def exit_code(fn, *args):
    try:
        fn(*args)
    except SystemExit as e:
        return e.code
    return 0


store = MemoryKeyring()
keyring.set_keyring(store)
slsk.CONFIG_PATH = Path(tempfile.mkdtemp()) / "metrolist-slsk" / "config.json"


async def fake_server(config):
    if config["password"] != "right":
        raise AuthenticationError("INVALIDPASS", "authentication failed")


slsk.check_login = fake_server


def login(password):
    sys.stdin = io.StringIO(password + "\n")
    return exit_code(slsk.login, ["--username", "me", "--password-stdin"])


# A rejected password is not saved.
assert login("wrong") == slsk.EXIT_NOT_LOGGED_IN
assert store.items == {} and not slsk.CONFIG_PATH.exists()
assert exit_code(slsk.load_config) == slsk.EXIT_NOT_LOGGED_IN

# An accepted password goes to the keychain only; the config holds the username, readable by the owner only.
assert login("right") == 0
assert store.items == {("metrolist-slsk", "me"): "right"}
saved = json.loads(slsk.CONFIG_PATH.read_text())
assert saved == {"username": "me"} and slsk.CONFIG_PATH.stat().st_mode & 0o777 == 0o600
assert slsk.load_config()["password"] == "right"
assert exit_code(slsk.status) == 0

# A plaintext password left in the config is ignored.
slsk.CONFIG_PATH.write_text(json.dumps({"username": "other", "password": "plain"}))
assert exit_code(slsk.load_config) == slsk.EXIT_NOT_LOGGED_IN

# Logout removes the keychain entry and the username.
slsk.write_config({"username": "me"})
slsk.logout()
assert store.items == {} and json.loads(slsk.CONFIG_PATH.read_text()) == {}
assert exit_code(slsk.status) == slsk.EXIT_NOT_LOGGED_IN
print("ok")
