{
  description = "Development environment for pierdol-auth-paper";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forEachSystem = nixpkgs.lib.genAttrs systems;
    in {
      devShells = forEachSystem (system:
        let pkgs = import nixpkgs { inherit system; };
        in {
          default = pkgs.mkShell {
            packages = [ pkgs.jdk21 pkgs.jdk25 pkgs.gradle pkgs.cacert pkgs.coreutils pkgs.gnugrep ];
            JAVA_HOME = pkgs.jdk21.home;
            JDK25_HOME = pkgs.jdk25.home;
            ORG_GRADLE_JAVA_INSTALLATIONS_FROM_ENV = "JDK25_HOME";
          };
        });
    };
}
