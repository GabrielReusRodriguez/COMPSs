
#!/bin/bash

workingDir=$1
cd $workingDir

tracing=$2
cachePosition=0
if [ $tracing == "true" ]
then
    node=$3
    scriptDir=`dirname $0`
    $scriptDir/trace.sh package $workingDir $node
    cache=$4
    cachePosition=4
else
    cache=$3
    cachePosition=3
fi

#Gabriel add
i=0
if [ "$cache" == "true" ]
then
	for param in $@
	do
		if [[ $i > $cachePosition  || $i == $cachePosition ]]; then
			rm -rf $param/*.IT
		fi
		i=$((i+1))
		
	done
fi	

#End Gabriel add
rm -rf *.IT
