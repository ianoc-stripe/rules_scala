#!/bin/bash

set -eou pipefail

FMT_SKYLINT=false ./lint.sh check
