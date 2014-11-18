#!/bin/sh

# Library references stored in the manifest file
LOCALCLASSPATH=
export JAVAHOME=${JAVAHOME:-/usr}
exec ${JAVAHOME}/bin/java -server -XX:+TieredCompilation -classpath ${LOCALCLASSPATH} -Xmx1024M -jar $HOME/occp/source/lib/occp.jar "$@"
