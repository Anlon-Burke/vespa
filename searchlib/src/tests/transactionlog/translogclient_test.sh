#!/bin/bash
# Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
set -e
rm -rf test7 test8 test9 test10 test11 test12 test13 testremove
$VALGRIND ./searchlib_translogclient_test_app
rm -rf test7 test8 test9 test10 test11 test12 test13 testremove
