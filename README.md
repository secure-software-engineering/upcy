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
- Clone the SootDiff repository `git clone https://github.com/anddann/upcy-base`
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
- Build UpCy and its docker container `mvn clean compile package`
