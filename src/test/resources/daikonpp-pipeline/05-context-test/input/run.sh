#!/usr/bin/env bash
set -euo pipefail

# Compile all Java files into proper package structure
javac -d . com/example/*.java

# Run the program
java com.example.Main
