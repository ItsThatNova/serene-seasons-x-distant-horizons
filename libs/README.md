# libs/

Place the following jars here before building:

- A Serene Seasons Fabric jar for 1.21.1 (e.g. `SereneSeasons-fabric-1.21.1-10.1.0.1.jar`)
- A Distant Horizons jar for 1.21.1 (e.g. `DistantHorizons-2.4.5-b-1.21.1-fabric-neoforge.jar`)

The build script picks them up via wildcard patterns (`SereneSeasons*.jar` and
`DistantHorizons*.jar`), so exact filenames do not matter as long as only one of each
is present.

These are compile-only dependencies and are not bundled into the output jar.
The `*.jar` rule in `.gitignore` excludes all jars by default; if you want to track
the libs jars in git, the `!libs/*.jar` exception line keeps them included.
