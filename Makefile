CODEDIR=./occp/
BUILDDIR=./build/
SOURCES=$(shell find ${CODEDIR} -name '*.java')
OUTPUT=./lib
DOC=./doc
CLASSPATH:=${OUTPUT}/commons-codec-1.9.jar:${OUTPUT}/commons-compress-1.6.jar:${OUTPUT}/commons-io-2.4.jar:${OUTPUT}/commons-lang.jar:${OUTPUT}/commons-net.jar:${OUTPUT}/commons-validator-1.4.0.jar:${OUTPUT}/vboxjws.jar:${OUTPUT}/vim25.jar:${OUTPUT}/fat32-lib-0.7-SNAPSHOT.jar:${OUTPUT}/bcpkix-jdk15on-149.jar:${OUTPUT}/bcprov-jdk15on-149.jar:${OUTPUT}/jsch-0.1.50.jar:$(CLASSPATH)

.SUFFIXES: .java .class

.PHONY: all

all: build ${OUTPUT}/occp.jar ${DOC}/index.html

build:
	@mkdir build doc

# Must fix paths because we are building in a different place
${OUTPUT}/occp.jar: $(subst ${CODEDIR},${BUILDDIR},${SOURCES:.java=.class})
	jar cmf MANIFEST.MF ${OUTPUT}/occp.jar -C ${BUILDDIR} .

${BUILDDIR}%.class: $(addprefix ${CODEDIR},%.java)
	javac -sourcepath ${CODEDIR} -d ${BUILDDIR} -J-Xms256M -J-Xmx256M -classpath ${CLASSPATH} $?

${DOC}/index.html: ${OUTPUT}/occp.jar
	javadoc -sourcepath ${CODEDIR} -d ${DOC} -classpath ${CLASSPATH} ${SOURCES}

clean:
	rm -rf $(OUTPUT)/occp.jar build doc
