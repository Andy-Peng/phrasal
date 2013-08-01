#!/bin/sh

echo "Making Phrasal release tar ball"
echo "JAVANLP_HOME set to $JAVANLP_HOME"

cd $JAVANLP_HOME

gitBranch=`git branch | grep "*" | cut -d " " -f 2`
echo "GIT branch: " $gitBranch
if [ "$gitBranch" = "master" ]; then
    echo "PASS: GIT branch " $gitBranch
else
    echo "FAIL: GIT should be on branch master, is on " $gitBranch
    exit -1
fi

gitFetch=`git fetch -v --dry-run 2>&1 | grep master`
gitExpectedFetch=" = [up to date]      master     -> origin/master"
echo "Result of 'git fetch -v --dry-run':"
echo "  " $gitFetch
if [ "$gitFetch" = "$gitExpectedFetch" ]; then
    echo "PASS: Repository checkout is current"
else
    echo "FAIL: Repository checkout is NOT current"
    echo "Please git pull before making a distribution"
    exit -1
fi

gitPush=`git push --dry-run 2>&1`
gitExpectedPush="Everything up-to-date"
echo "Result of 'git push --dry-run':"
echo "  " $gitPush
if [ "$gitPush" = "$gitExpectedPush" ]; then
    echo "PASS: no unpushed changes"
else
    echo "FAIL: there are committed but unpushed changes"
    exit -1
fi

gitUntracked=`git status 2>&1 | grep Untracked`
if [ "$gitUntracked" = "" ]; then
    echo "PASS: no untracked changes"
else
    echo "FAIL: untracked changes detected"
    echo $gitUntracked
    exit -1
fi

gitUncommitted=`git status 2>&1 | grep "Changes to be committed"`
if [ "$gitUncommitted" == "" ]; then
    echo "PASS: no changes ready to be committed"
else
    echo "FAIL: detected changes ready to be committed"
    echo $gitUncommitted
    exit -1
fi

gitCommitDryrun=`git commit --dry-run -am foo 2>&1 | grep -e modified -e deleted | grep -v make-phrasal-release`
if [ "$gitCommitDryrun" == "" ]; then
    echo "PASS: no uncommitted changes detected"
else
    echo "FAIL: uncommitted changes detected"
    echo $gitCommitDryrun
fi

echo "Current time: " `date`
echo "Building in $PWD"

ant all
if [ $? = 0 ]; then
  echo "PASS: repository builds succuessfully"
else
  echo "FAIL: repository has build errors"
  exit -1
fi 
cd -

rm -rf phrasal.$1
mkdir phrasal.$1 || exit

cp -r src scripts README.txt LICENSE.txt phrasal.$1 || exit
cp userbuild.xml  phrasal.$1/build.xml || exit

perl ../../bin/gen-dependencies.pl -depdump depdump -srcjar src.jar -classdir ../core/classes -srcdir ../core/src \
    edu.stanford.nlp.classify.LogisticClassifier \
    edu.stanford.nlp.classify.LogisticClassifierFactory \
    edu.stanford.nlp.trees.DependencyScoring \
    
mkdir -p phrasal.$1/src
cd phrasal.$1/src
jar xf ../../src.jar edu
cd -

# TODO: if these dependencies start getting more complicated, find an
# automatic way to solve them (would need to make gen-dependencies
# work across multiple directories)
mkdir -p phrasal.$1/src/edu/stanford/nlp/lm
cp ../more/src/edu/stanford/nlp/lm/* phrasal.$1/src/edu/stanford/nlp/lm || exit

mkdir -p phrasal.$1/src/edu/stanford/nlp/stats
cp ../more/src/edu/stanford/nlp/stats/OpenAddressCounter.java phrasal.$1/src/edu/stanford/nlp/stats/OpenAddressCounter.java || exit

mkdir -p phrasal.$1/src/edu/stanford/nlp/classify
cp ../more/src/edu/stanford/nlp/classify/LinearRegressionFactory.java phrasal.$1/src/edu/stanford/nlp/classify || exit
cp ../more/src/edu/stanford/nlp/classify/LinearRegressionObjectiveFunction.java phrasal.$1/src/edu/stanford/nlp/classify || exit
cp ../more/src/edu/stanford/nlp/classify/LinearRegressor.java phrasal.$1/src/edu/stanford/nlp/classify || exit
cp ../more/src/edu/stanford/nlp/classify/Regressor.java phrasal.$1/src/edu/stanford/nlp/classify || exit
cp ../more/src/edu/stanford/nlp/classify/RegressionFactory.java phrasal.$1/src/edu/stanford/nlp/classify || exit
cp ../more/src/edu/stanford/nlp/classify/CorrelationLinearRegressionObjectiveFunction.java phrasal.$1/src/edu/stanford/nlp/classify || exit

mkdir -p phrasal.$1/lib || exit
cp lib/berkeleyaligner.jar phrasal.$1/lib || exit
cp ../core/lib/junit.jar phrasal.$1/lib || exit
cp ../core/lib/commons-lang3-3.1.jar phrasal.$1/lib || exit
cp ../more/lib/fastutil.jar phrasal.$1/lib || exit
cp lib/je-4.1.10.jar phrasal.$1/lib || exit
cp lib/guava-11.0.2.jar phrasal.$1/lib || exit

mkdir `pwd`/phrasal.$1/classes
mkdir `pwd`/phrasal.$1/lib-nodistrib

export CLASSPATH=.
export CORENLP=`ls -dt /u/nlp/distrib/stanford-corenlp-full-201*-0*[0-9] | head -1`

(cd  phrasal.$1/; ./scripts/first-build.sh all)
if [ $? = 0 ]; then
   echo "PASS: User distribution builds successfully"
else
   echo "FAIL: User distribution has build errors"
   exit -1
fi

jar -cf phrasal.$1/phrasal.$1.jar -C phrasal.$1/classes edu

echo "Running phrasal integration test" 
export CLASSPATH=$CLASSPATH:`pwd`/phrasal.$1/classes
for jarFile in $CORENLP/*.jar; do
  export CLASSPATH=$CLASSPATH:$jarFile
done
export CLASSPATH=$CLASSPATH:`pwd`/phrasal.$1/lib/fastutil.jar
echo $CLASSPATH

scripts/standard_mert_test.pl distro.$1

if [ $? = 0 ]; then
  echo "PASS: Phrasal integration test"
else
  echo "FAIL: Phrasal integration test"
  echo "Log file in /u/nlp/data/mt_test/mert:"
  echo `ls -t  /u/nlp/data/mt_test/mert/*.log | head -1`
  echo "End of log dump for FAIL: Phrasal integration test"
  cat `ls -t  /u/nlp/data/mt_test/mert/*.log | head -1`
  echo "FAIL: Phrasal integration test"
  exit -1
fi

#rm -rf phrasal.$1/classes/*
rm -rf phrasal.$1/lib-nodistrib/*

# This time, look without excluding make-phrasal-release so that we can stash it if needed
gitCommitDryrun=`git commit --dry-run -am foo 2>&1 | grep -e modified -e deleted`
if [ "$gitCommitDryrun" == "" ]; then
    stash = "false"
else
    stash = "true"
    echo "Stashing your changes to make-phrasal-release.sh.  If something goes wrong, you will need to run"
    echo "  git stash pop"
    git stash
fi

git checkout phrasal-releases
ls $JAVANLP_HOME/$1
if [ $? == 0 ]; then
    echo "Removing old $1 distribution branch from phrasal-releases"
    echo "If the script does not output PASS on the next line, you may need to clean up the phrasal-releases branch"
    # TODO: this part may need some testing 
    git rm $JAVANLP_HOME/$1 || exit
    git checkin -m "Remove old $1 distribution branch from phrasal-releases" || exit
    git push || exit
    echo "PASS cleaning up $1 distribution"
fi

echo "Archiving distribution phrasal-releases/$1"

mkdir -p $JAVANLP_HOME/$1 || exit
cp -r phrasal.$1/src $JAVANLP_HOME/$1 || exit
cp -r phrasal.$1/lib $JAVANLP_HOME/$1 || exit
git add $JAVANLP_HOME/$1 || exit
git commit -m "Archiving distribution for phrasal release $1" || exit
git push || exit

git checkout master || exit
if [ "$stash" == "true" ]; then
    git stash pop
fi

tar -czf phrasal.$1.tar.gz phrasal.$1

if [ $? = 0 ]; then
  echo "SUCCESS: Stanford Phrasal distribution phrasal.$1.tar.gz successfully built"
else
  echo "FAIL: Tar went wrong somehow"
  exit -1
fi
