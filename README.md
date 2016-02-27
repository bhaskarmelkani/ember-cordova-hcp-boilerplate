# Ember-cordova-hcp-boilerplate

This README outlines the details of collaborating on this Ember application.
A short introduction of this app could easily go here.

## Prerequisites

You will need the following things properly installed on your computer.

* [Git](http://git-scm.com/)
* [Node.js](http://nodejs.org/) (with NPM)
* [Bower](http://bower.io/)
* [Ember CLI](http://www.ember-cli.com/)
* [PhantomJS](http://phantomjs.org/)

## Installation

* `git clone <repository-url>` this repository
* change into the new directory
* `npm install`
* `bower install`

## Running / Development

* `ember server`
* Visit your app at [http://localhost:4200](http://localhost:4200).

### Code Generators

Make use of the many generators for code, try `ember help generate` for more details

### Running Tests

* `ember test`
* `ember test --server`

### Building

* `ember build` (development)
* `ember build --environment production` (production)

### Deploying

Specify what it takes to deploy your app.

## Further Reading / Useful Links

* [ember.js](http://emberjs.com/)
* [ember-cli](http://www.ember-cli.com/)
* Development Browser Extensions
  * [ember inspector for chrome](https://chrome.google.com/webstore/detail/ember-inspector/bmdblncegkenkacieihfhpjfppoconhi)
  * [ember inspector for firefox](https://addons.mozilla.org/en-US/firefox/addon/ember-inspector/)

## Added support:-

- [Cordova](https://github.com/poetic/ember-cli-cordova)
    
- [Crosswalk](https://github.com/crosswalk-project/cordova-plugin-crosswalk-webview)
- [Hot Code Push](https://github.com/nordnet/cordova-hot-code-push)
- [Hot Code Push Cli](https://github.com/nordnet/cordova-hot-code-push-cli)


1. 	```ember install ember-cli-cordova```
2. 	```cordova plugin add cordova-plugin-crosswalk-webview```
    
2.    Edit config/environment.js ,
    
3.    Change `locatonType` to `defaultLocationType`.
       [#Reference](https://github.com/poetic/ember-cli-cordova/blob/master/docs/getting-started.md#developing-the-app)
    
 4.   ```ember generate cordova-init com.bhaskarmelkani.ember_cordova_hcp_boilerplate```
    
    
5. ```ember cordova:prepare```
6. ```cd cordova/```
7. ```cordova plugin add cordova-hot-code-push-plugin```
8. ```sudo npm install -g cordova-hot-code-push-cli```
9.  Added following code snippt in cordova/cofig.xml, inside ``widget`` tag.
```
<chcp>
  <config-file url="https://test.company_server.com/mobile/www/chcp.json"/>
</chcp>
```
10. ```cordova-hcp init```
11. Fill in details.
12. [Build and deploy the code to S3](https://github.com/nordnet/cordova-hot-code-push-cli#normal-workflow-scheme)