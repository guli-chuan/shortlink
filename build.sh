#!/bin/bash
# 打包成可执行 jar：target/shortlink.jar
mvn -B clean package -Dmaven.test.skip=true
