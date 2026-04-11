#!/bin/bash

mvn exec:java -pl loom/fixture -Dexec.mainClass="io.metaloom.loom.test.PoolSetupRunner"