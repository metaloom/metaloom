#!/bin/bash

docker run --rm --name mariadb -p 3306:3306 -e MYSQL_ROOT_PASSWORD=finger mariadb:10.5.9-focal