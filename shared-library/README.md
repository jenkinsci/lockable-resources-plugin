# Jenkins lockable-resources-shared-library

Jenkins shared library to extends [lockable-resources-plugin](https://github.com/jenkinsci/lockable-resources-plugin) as a groovy code.

The goal is to extend lockable-resources-plugin functionality in simple way and provide a solution for end-user (administrators) with much more then one simple lock() step.

Many users has own solutions like create resources, or check it is isFree().
A lot of them are done in uncommon way and a lot of them make some useful magic.
This shall helps to all Jenkins administrators to interact with lockable-resources:

+ without coding own code
+ spare maintenance on own side
+ spare testing after each plugin and jenkins update
+ customizing
+ share ideas with community
+ do not copy examples from untrusted zones in to your code
+ be sure your solutions are done in proper / supported way

---

## Usage

The lockable-resources-plugin will setup on your Jenkins instance new global shared library named **lockable-resources-shared-library**.

**Default version** of branch use release-IDs. It will be automatically updated on every plugin update. That means you does not care about that in normal case.
This grants you, that the plugin and shared library works together.
Of cores you can change the branch name every time you need. For example to *master*.
In this case you will pull last commit from master. It is fine to get the changes before officially release. But it is potential risk, because shared-lib may not work with you plugin version.

Enjoy in your pipelines.
<!-- TBD: describe detailed steps, and hallo world project-->

### Customizing

Fork this repository and change what you want.

> You can share your ideas with community, when you create pull-request into this repository. This will help you to eliminate maintenance and help the community with more power.

> Keep in mind, that we can not care about your changes in forked repository. It means, when this repository became an update, you need merge changes by your self.

---

## Report an Issue

Please report issues and enhancements through the [GitHub](https://github.com/jenkinsci/lockable-resources-plugin/issues/new/choose).

If you have an example to share, please create a [new documentation issue](https://github.com/jenkinsci/lockable-resources-plugin/issues/new?assignees=&labels=documentation&template=3-documentation.yml) and provide additional examples as a [pull request](https://github.com/jenkinsci/lockable-resources-plugin/pulls) to the repository.

If you have a question, please open a [GitHub issue](https://github.com/jenkinsci/lockable-resources-plugin/issues/new/choose) with your question.

---

## Contributing

Contributions are welcome, please refer to the separate [CONTRIBUTING](CONTRIBUTING.md) document for details on how to proceed!

Join [Gitter channel](https://gitter.im/jenkinsci/lockable-resources) to discuss your ideas with the community.

---

## License

All source code is licensed under the MIT license.
See [LICENSE](../LICENSE.txt)
