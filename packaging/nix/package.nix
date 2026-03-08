{ lib, stdenv, appimageTools, fetchurl, release }:
let
  pname = "metrolist";
  version = release.version;
  asset = release.linux.${stdenv.hostPlatform.system};
  src = fetchurl { inherit (asset) url hash; };
  contents = appimageTools.extract { inherit pname version src; };
in
appimageTools.wrapType2 {
  inherit pname version src;
  extraPkgs = pkgs: with pkgs; [
    gtk3 webkitgtk_4_1 libsecret dbus alsa-plugins
  ];
  extraInstallCommands = ''
    install -Dm644 ${contents}/Metrolist.desktop $out/share/applications/metrolist.desktop
    substituteInPlace $out/share/applications/metrolist.desktop \
      --replace-fail 'Exec=bin/Metrolist' 'Exec=metrolist'
    install -Dm644 ${contents}/Metrolist.png $out/share/icons/hicolor/512x512/apps/Metrolist.png
  '';
  meta = {
    description = "YouTube Music desktop client";
    homepage = "https://github.com/MetrolistGroup/Metrolist";
    license = lib.licenses.gpl3;
    platforms = [ "x86_64-linux" "aarch64-linux" ];
    mainProgram = pname;
  };
}
