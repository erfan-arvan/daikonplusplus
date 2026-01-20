#!/bin/bash
set -e

# We are already in the WORKING PROJECT ROOT
# src/Main.java is instrumented

javac src/Main.java
java -cp src Main

