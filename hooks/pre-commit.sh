#!/bin/sh
# hook identifies jira ids in branch names, adds on more goodies if we are a hotifx or release branch and attempts to prevent blank commit messages...although this needs a bit more work... 


COMMIT_FILE=$1
COMMIT_MSG=$(cat $1)

#First and foremost check whether the commit message is blank. if so then abort the commit. strip any spaces out too
#this one only works at present when someone tries to do git commit -m "" or git commit -m "  "
#if anyone uses tools such as sourcetree, then these GUIs typically present a prompt...eg. "do you want to commit wihtout a message" and pass in optional attributes to git commit to allow this.

if [[ -z "${COMMIT_MSG// }" ]]; then
	echo "You cannot have an empty commit message. Please enter something meaningful!"
	exit 1
fi


#if we get to this point then prepend different things onto our commit message...
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
#CURRENT_BRANCH="hotfix/jira-123"

##try identify a jira ticket number, and also uppercase it
JIRA_ID=$(echo "$CURRENT_BRANCH" | tr 'a-z' 'A-Z' | grep -Eo "[A-Z]+-[0-9]+")
HOTFIX_ID=$(echo "$CURRENT_BRANCH" | tr 'a-z' 'A-Z' | grep -Eo "HOTFIX")
RELEASE_ID=$(echo "$CURRENT_BRANCH" | tr 'a-z' 'A-Z' | grep -Eo "RELEASE")

#if we have no jira then use current branch name instead
if [ [-z "$JIRA_ID"] ];then
	JIRA_ID=$CURRENT_BRANCH
fi


if [ ! -z "$HOTFIX_ID" ]; then
	echo "[$JIRA_ID] [HOTFIX]: $COMMIT_MSG" > $COMMIT_FILE
	echo "JIRA ID '$JIRA_ID' AND HOTFIX, matched in current branch name, prepended BOTH to commit message."

elif [ ! -z "$RELEASE_ID" ]; then
	echo "[$JIRA_ID] [RELEASE]: $COMMIT_MSG" > $COMMIT_FILE
	echo "RELEASE branch found. '[RELEASE]' prepended to commit message."

elif [[ ! -z "$JIRA_ID" ]]; then
	echo "[$JIRA_ID]: $COMMIT_MSG" > $COMMIT_FILE
	echo "JIRA ID '$JIRA_ID', matched in current branch name, prepended to commit message. (Use --no-verify to skip)"

else
	echo "[$CURRENT_BRANCH]: $COMMIT_MSG" > $COMMIT_FILE
fi
