# Librarian with ForgeGradle

To use Librarian with ForgeGradle, you must be on ForgeGradle 5 and Gradle 7.1.1 or higher.

First, add `maven { url = 'https://maven.parchmentmc.org' }` to your buildscript repositories.
Example:
```groovy
buildscript {
    repositories {
        maven { url = 'https://maven.minecraftforge.net' }
        maven { url = 'https://maven.parchmentmc.org' }
```

Add Librarian as a buildscript dependency with `classpath 'org.parchmentmc:librarian:1.+'`.
Example:
```groovy
buildscript {
    ...
    dependencies {
        classpath group: 'net.minecraftforge.gradle', name: 'ForgeGradle', version: '5.1.+', changing: true
        classpath 'org.parchmentmc:librarian:1.+'
```

Apply the Librarian ForgeGradle plugin **below the ForgeGradle plugin** using `apply plugin: 'org.parchmentmc.librarian.forgegradle'`.
Example:
```groovy
apply plugin: 'net.minecraftforge.gradle'
apply plugin: 'org.parchmentmc.librarian.forgegradle'
```

Finally, update your mappings channel and version to use Parchment.
For example, to use Parchment export 2021.08.15 for 1.17.1, change your mappings channel to `parchment` and mappings version to `2021.08.15-1.17.1`.
It should end up like so:
```groovy
mappings channel: 'parchment', version: '2021.08.15-1.17.1'
```