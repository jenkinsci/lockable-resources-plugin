# Contributing

Fork this repository. Make your changes, tests it and provide a pull-request. That`s it.

## Git workflow

To eliminate weird and often changes in master branch we provide long time **develop** branch.
That means all changes (feature requests, bug fixing) must be merged in to **develop** branch first
and not into **master**.
The branch **develop** will be merged in to master on demand.

```
                             R1              R2               Rn
--- master ----------------------------------------- ... ------------
            \--- develop ---/\--- develop ---/\--- develop ---/
              \-feature1-/ /     /
              \-feature2--/     /
                    \-feature3-/
```

## Setup environment

Lockable resource plugin shall setup everything you need on demand.

## Build and test

TBD
