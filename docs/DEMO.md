# Demo script and troubleshooting

## Two-phone demo

| Step | Faculty phone | Student phone |
|---|---|---|
| 1 | Create account → **Faculty** (enter the faculty access code) | Create account → **Student** (note "linked to this phone") |
| 2 | **New course** → "Mobile Application Development", `CS301` → note the join code | |
| 3 | | **+** → enter the join code → the course appears |
| 4 | **Start class** → *Within 50 m* → live QR with countdown | |
| 5 | | **Mark attendance** → fingerprint/PIN → location → scan QR → "You're marked present" |
| 6 | Count goes to 1 and the name appears live | |
| 7 | **Proxy demo 1:** screenshot the QR, wait about 40 s | Scan the screenshot → *"This code has expired"* |
| 8 | | **Proxy demo 2:** Mark attendance again → *"Already marked"* |
| 9 | | **Proxy demo 3:** sign in to this account on another phone → *"linked to a different phone"* |
| 10 | **End class** → report with present and absent lists | Course shows the percentage and the 75% hint |
| 11 | Course → **Students** tab → percentages; share icon → **Export register** (CSV) | |

For the geofence demo, start the class with 25 m and walk out of the room with the student phone.

Only one phone? Run the faculty side on the Android Studio emulator (set its location under
*Extended controls → Location*, and pick 100 m) and scan the laptop screen with your phone.

## Troubleshooting

| Problem | Fix |
|---|---|
| Sign-up says "You're offline" | The placeholder `google-services.json` only works on the emulator. Use your own Firebase project on real phones, and check the phone has internet. |
| "This account is linked to a different phone" | Expected after changing phones. Faculty → course → Students → tap the student → **Reset phone**. |
| "This phone is already linked to another student account" | One phone per student. Use the phone that student registered on. |
| "That faculty access code is wrong" | Check `config/faculty` → `code` in the Firestore console. |
| Identity step fails | Set a screen lock or fingerprint in the phone's Settings. |
| Location step fails | Turn on Location (high accuracy), allow the permission, move near a window. |
| "This code has expired" | Scan the code that's on screen now; each one lasts about 15 s. |
| Gradle can't download Gradle 9.6.0 | Some networks time out on GitHub's download servers. Retry on another network, or copy an existing Gradle 9.6.0 into `~/.gradle/wrapper/dists/`. |
| "jlink executable … does not exist" | Gradle picked a JRE without `jlink`. Settings → Build → Gradle → Gradle JDK → the bundled *jbr*. |
| Firebase emulator says "Could not spawn `java -version`" | Put Android Studio's bundled Java on the PATH: `C:\Program Files\Android\Android Studio\jbr\bin`. |
