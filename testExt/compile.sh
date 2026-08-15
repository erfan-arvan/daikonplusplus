cat > compile.sh << 'EOF'
#!/usr/bin/env bash
set -e

# External compiler (this IS the compiler)
javac src/Main.java
EOF

