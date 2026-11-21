TeamEchestAddon (addon for justTeams - justTeams itself is NOT modified)

1. /team echest  ->  /team enderchest   (also works for /guild /clan /party)
2. Teammates can open the same team ender chest at the same time instead of
   getting "This team's Ender Chest is currently being used on another server".
3. A stuck ender chest lock (nobody viewing it for 15s) is cleared automatically.

Build:   mvn clean package
Output:  target/TeamEchestAddon.jar -> put in /plugins on EVERY server that runs
         justTeams, then restart. Remove the old TeamEchestAlias jar.
