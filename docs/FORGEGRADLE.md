# Librarian with ForgeGradle

To use Librarian with ForgeGradle, you must be on ForgeGradle 5 and Gradle 7.1.1 or higher.
Start by adding the Librarian plugin using one of the methods below:

<details>
<summary>Legacy plugin application (1.18- MDK)</summary>
    
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
</details>

<details>
<summary>New plugin DSL (1.19+ MDK)</summary>

First, add `maven { url = 'https://maven.parchmentmc.org' }` to your plugin repositories in the `settings.gradle` file.
Example:
```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://maven.minecraftforge.net/' }
        maven { url = 'https://maven.parchmentmc.org' }
```

Apply the Librarian ForgeGradle plugin **below the ForgeGradle plugin** using `id 'org.parchmentmc.librarian.forgegradle' version '1.+'`.
Example:
```groovy
plugins {
    // Other plugins like maven-publish, idea, eclipse, etc. go here
    id 'net.minecraftforge.gradle' version '5.1.+'
    id 'org.parchmentmc.librarian.forgegradle' version '1.+'
}
``` 
</details>

Finally, update your mappings channel and version to use Parchment.
For example, to use Parchment export 2021.08.15 for 1.17.1, change your mappings channel to `parchment` and mappings version to `2021.08.15-1.17.1`.
It should end up like so:
```groovy
mappings channel: 'parchment', version: '2021.08.15-1.17.1'
```

## Cross-Version Mappings

As of Librarian **1.2.0**, you can now use Parchment mappings built for any version of Minecraft on top of any other version of Mojang mappings _(mojmaps)_.
This is useful when developing for versions where a Parchment release export has not been made.

This feature ensures you have a complete mapping set for classes, method, and fields based on your environment's Minecraft version while also allowing the use of Parchment exports made for any Minecraft version. 
Librarian will try to pull Parchment data where it can into your development environment. 
New classes or modified methods (due to parameter changes) will not have parameter names or javadocs from Parchment applied.

To use this feature, prepend the Minecraft version of the Parchment export and append the target Mojmaps version to the mappings version, both separated by hyphens.
For example, to use Parchment export 2022.03.06 for 1.18.1 but for _Mojmaps_ version 1.18.2, change your mappings version to `1.18.1-2022.03.06-1.18.2`.
It should end up like so:
```groovy
mappings channel: 'parchment', version: '1.18.1-2022.03.06-1.18.2'
```
