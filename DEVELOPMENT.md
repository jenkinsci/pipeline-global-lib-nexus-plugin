# Development on this plugin

The jenkins.io website already contains quite a lot of information on how to build and test plugins, see:

* https://www.jenkins.io/doc/developer/plugin-development/

After that, cloning this repo and building this using `mvn clean install` should be sufficient.
To test the artifact, manually upload it to your Jenkins environment.

## How to make an official release?

You are a maintainer of this repository and need to release a fix? Please follow the instructions below:

* Make sure you fulfilled the requirements that can be found [here](https://jenkins.io/doc/developer/publishing/releasing/)
* Be sure to have no mirror defined in your global settings.xml (if needed pass --global-settings an-empty-settings.xml as an additional parameter)
* Place yourself on the master branch of the [jenkinsci/pipeline-global-lib-nexus-plugin repo](https://github.com/jenkinsci/pipeline-global-lib-nexus-plugin)
* ```mvn release:prepare``` and let the plugin increase the patch number (or increase yourself the minor or major)
* ```mvn release:perform``` to deploy the actual artifacts
* If things go wrong ```mvn release:clean```
* Create a release using the GitHub UI and describe the changes.

Within 24 hours the new release will be available via plugins.jenkins.io
