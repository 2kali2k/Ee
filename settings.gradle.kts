pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ee"

include(":app")

// core
include(":core:core-model")
include(":core:core-common")
include(":core:core-file")
include(":core:core-database")
include(":core:core-security")
include(":core:core-network")

// design system
include(":design-system")

// features
include(":feature:feature-home")
include(":feature:feature-filemanager")
include(":feature:feature-imageviewer")
include(":feature:feature-video")
include(":feature:feature-audio")
include(":feature:feature-editor")
include(":feature:feature-archives")
include(":feature:feature-vault")
include(":feature:feature-network")
include(":feature:feature-cloud")
include(":feature:feature-transfers")
include(":feature:feature-settings")
include(":feature:feature-stats")

// filesystem providers
include(":providers:provider-local")
include(":providers:provider-usb")
include(":providers:provider-smb")
include(":providers:provider-sftp")
include(":providers:provider-ftp")
include(":providers:provider-ftpsrv")
include(":providers:provider-webdav")
include(":providers:provider-http")
include(":providers:provider-archive")
include(":providers:provider-media")
include(":providers:provider-vault")
include(":providers:provider-cloud")
