#!/bin/bash
rm -rf ./d*/
for i in {1..15}
do
	mkdir "./d$i"
done

echo -en "Hello\nGreetings All\nOne for All\nand\nAll for One" | python3 populate.py d1/ hello.txt