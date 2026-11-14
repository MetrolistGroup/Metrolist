{
  description = "Metrolist desktop release packages";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/6f06d204d8f2d8761d9da78778745974a3216381";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forSystems = nixpkgs.lib.genAttrs systems;
      releaseFile = ./packaging/release.json;
      release = if builtins.pathExists releaseFile then
        builtins.fromJSON (builtins.readFile releaseFile)
      else
        throw "Generate packaging/release.json from a stable release with scripts/generate_distribution.py. See docs/distribution.md.";
    in {
      packages = forSystems (system:
        let
          pkgs = import nixpkgs { inherit system; };
          metrolist = pkgs.callPackage ./packaging/nix/package.nix { inherit release; };
        in { inherit metrolist; default = metrolist; });
      apps = forSystems (system: {
        default = {
          type = "app";
          program = "${self.packages.${system}.default}/bin/metrolist";
        };
      });
    };
}
