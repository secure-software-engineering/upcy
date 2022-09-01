!#/bin/bash
ZENODOFILE=http://localhost:8000/experimental-results_dataset.zip
curDir=${PWD}

# download the file
wget $ZENODOFILE -P /tmp/
mytmpdir=$(mktemp -d 2>/dev/null || mktemp -d -t 'upcytmpdir')
unzip /tmp/experimental-results_dataset.zip -d $mytmpdir
# rename folder to match expected input
mv $mytmpdir/results_update-steps $mytmpdir/projects
# create file with expected name
cd $mytmpdir && zip -r project_input_recommendation.zip ./projects
mv -f project_input_recommendation.zip $curDir
# clean up
cd $curDir
rm -rf $mytmpdir