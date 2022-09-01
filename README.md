# UpCy - Safely Updating Outdated Dependencies

Recent research has shown that developers hesitate to update dependencies and mistrust automated approaches such as
Dependabot, since they are afraid of introducing incompatibilities that break their project. In fact, these approaches
only suggest na\"ive updates for a single outdated library but do not ensure compatibility with other dependent
libraries in the project. To alleviate this situation and support developers in finding an update with minimal
incompatibilities, we present UpCY. UpCy computes the unified dependency graph (combining the project's dependency graph
and call graph) and uses min-(s,t)-cut algorithms to find updates of a library that have the least amount of
incompatibilities to other project dependencies.

## Prerequisites
To execute und build UpCy yourself you need to have the following software installed on your machine:
- JDK >= 1.8.0_231
- Maven >= 3.8.4
- Docker >= 20.10.17



## Setup

### Install SootDiff
 - Clone the SootDiff repository `git clone https://github.com/secure-software-engineering/sootdiff.git`
 - change into the folder `cd sootdiff`
 - Build and install SootDiff in your local maven repository `mvn clean compile install -DskipTests`
 

### Install Maven-EcoSystem (libraries for assessing the Neo4j DB of Maven Central)
- Clone the Maven-EcoSystem repository `git clone https://github.com/anddann/mvn-ecosystem-graph`
- change into the folder `cd mvn-ecosystem-graph`
- Build and install SootDiff in your local maven repository `mvn clean compile install`


### Install UpCy-Base (libraries for building dependency graph and running call graph analysis)
- Clone the UpCy-Base repository `git clone https://github.com/anddann/upcy-base`
- change into the folder `cd upcy-base`
- Build and install SootDiff in your local maven repository `mvn clean compile install`

###  Setup Graph Database of Maven Central (Neo4j) & Database of Binary- & Source-Code Incompatibilities (MongoDB)
Download the database files `incompabilities_mongodb.tar.gz` and `maven-central_neo4j.tar.gz` from <https://zendo.com/>.
Extract both files using the command `tar xzf <FileName>`. The unzipped folders contain the databases.
Then start an instance of a mongoDB and Neo4j database and mount these two folders as volumes.
A ready-to-use configuration is in the file `docker-compose-dbs.yml`.
To run it execute `docker-compose -f docker-compose-dbs.yml up`.
This fires ups the databases and mounts the extracted folders as volumes.
Then wait for the databases to start.

### Build UpCy 
- To build UpCy and its docker container run `mvn clean compile package`.


## Run UpCy

### Set environment variables
For connecting to the databases the following environment variables **must** be set with the concrete values.
- NEO4J_URL: bolt://localhost:7687
- NEO4J_USER: neo4j
- NEO4J_PASS DUMMYPASSWORD
- MONGO_USER: user
- MONGO_HOST: localhost
- MONGO_PW: DUMMYPASSWORD


## Run UpCy

## Execute on a Maven Module
To execute UpCy and its call graph analysis based on Soot, your Maven module must compile since Soot uses the bytecode class for call graph construction.
Further, all dependencies must resolve properly.

First, compile the module by running `mvn compile`.

Second, generate the dependency graph for the project by executing:
```
com.github.ferstl:depgraph-maven-plugin:4.0.1:graph -DshowVersions -DshowGroupIds -DshowDuplicates -DshowConflicts -DgraphFormat=json
```

Third, invoke the UpCy class `de.upb.upcy.MainMavenComputeUpdateSuggestion` with the following arguments:
* -dg,--dependency-graph <arg>   the generated dependency graph as json file
* -gav <arg>                     the GAV of the dependency to update in the form - group:artifact:version
* -module,--maven-module <arg>   path to the maven module containing the pom.xml
* -targetGav <arg>               the target GAV in the form - group:artifact:version

Fourth, UpCy will create a file `_recommendation_results.csv` in the module's folder with the computed update options.



### Re-Run experiments
The main class for re-running UpCy is `de.upb.upcy.MainComputeUpdateSuggestion`.
To re-run the experiments download the [experimental-results_dataset.zip](https://ZenodURL) and unzip it on your local machine.
Then pass the unzipped folder as an argument to the class `de.upb.upcy.MainComputeUpdateSuggestion`.
The code then clones each repository, and executes UpCy on each project and with each update step given in the `_update-steps.csv` files.


#### Re-Run experiments using the Docker pipeline
The docker pipeline allows you to re-run the UpCy experiments distributed on multiple machines using the docker-compose file `docker-compose-upcy-dockerized.yml`
To do so, the pipeline consists of **one** `rabbitmq` message broker container for distributing the workload, **one** `producer` container creating the tasks, and **multiple** worker containers that run UpCy.



Before running the containers copy the file `upcy.sample.env` to `upcy.env` and adapt the environment variables there.
For saving the results the containers connect to an external `FILESERVER_HOST` that you must specify in the env file.
The producer node reads as an input from `FILESERVER_HOST/project_input_recommendation.zip`. 
For creating the file from [experimental-results_dataset.zip](https://ZenodURL) run the bash script `prepare-inputfile.sh`
If you prefer to create the file manually keep in mind that
 - the file must contain a root folder `projects`
 - sub-folders with `repoOwner_repoName` and containing a `COMMIT` file
 - the sub-folders must contain the `_update-steps.csv` files
 - the example input is [experimental-results_dataset.zip](https://ZenodURL). Note the file does not have the root folder `projects`, thus you must unzip it and add the root folder yourself.