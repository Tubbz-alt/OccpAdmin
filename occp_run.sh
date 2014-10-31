#!/bin/sh

# Library references stored in the manifest file
export JAVAHOME=${JAVAHOME:-/usr}

# These two lines use .class files, not jar
#LOCALCLASSPATH=${PWD}/build/:$(find ${PWD}/lib -name occp.jar -prune -o -name '*.jar' -printf "%p:")
#exec ${JAVAHOME}/bin/java -server -XX:+TieredCompilation -classpath ${LOCALCLASSPATH} -Xmx1024M edu.uri.dfcsc.occp.OccpAdmin "$@"

# This version uses jar file, which must have built-in classpath and Main Class
exec ${JAVAHOME}/bin/java -server -XX:+TieredCompilation -jar lib/occp.jar "$@"
