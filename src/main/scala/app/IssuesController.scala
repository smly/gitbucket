package app

import jp.sf.amateras.scalatra.forms._

import service._
import IssuesService._
import util.{CollaboratorsAuthenticator, ReferrerAuthenticator, ReadableUsersAuthenticator, Notifier, Keys}
import util.Implicits._
import util.ControlUtil._
import org.scalatra.Ok

class IssuesController extends IssuesControllerBase
  with IssuesService with RepositoryService with AccountService with LabelsService with MilestonesService with ActivityService
  with ReadableUsersAuthenticator with ReferrerAuthenticator with CollaboratorsAuthenticator

trait IssuesControllerBase extends ControllerBase {
  self: IssuesService with RepositoryService with LabelsService with MilestonesService with ActivityService
    with ReadableUsersAuthenticator with ReferrerAuthenticator with CollaboratorsAuthenticator =>

  case class IssueCreateForm(title: String, content: Option[String],
    assignedUserName: Option[String], milestoneId: Option[Int], labelNames: Option[String])
  case class IssueEditForm(title: String, content: Option[String])
  case class CommentForm(issueId: Int, content: String)
  case class IssueStateForm(issueId: Int, content: Option[String])

  val issueCreateForm = mapping(
      "title"            -> trim(label("Title", text(required))),
      "content"          -> trim(optional(text())),
      "assignedUserName" -> trim(optional(text())),
      "milestoneId"      -> trim(optional(number())),
      "labelNames"       -> trim(optional(text()))
    )(IssueCreateForm.apply)

  val issueEditForm = mapping(
      "title"   -> trim(label("Title", text(required))),
      "content" -> trim(optional(text()))
    )(IssueEditForm.apply)

  val commentForm = mapping(
      "issueId" -> label("Issue Id", number()),
      "content" -> trim(label("Comment", text(required)))
    )(CommentForm.apply)

  val issueStateForm = mapping(
      "issueId" -> label("Issue Id", number()),
      "content" -> trim(optional(text()))
    )(IssueStateForm.apply)

  get("/:owner/:repository/issues")(referrersOnly {
    searchIssues("all", _)
  })

  get("/:owner/:repository/issues/assigned/:userName")(referrersOnly {
    searchIssues("assigned", _)
  })

  get("/:owner/:repository/issues/created_by/:userName")(referrersOnly {
    searchIssues("created_by", _)
  })

  get("/:owner/:repository/issues/:id")(referrersOnly { repository =>
    defining(repository.owner, repository.name, params("id")){ case (owner, name, issueId) =>
      getIssue(owner, name, issueId) map {
        issues.html.issue(
          _,
          getComments(owner, name, issueId.toInt),
          getIssueLabels(owner, name, issueId.toInt),
          (getCollaborators(owner, name) :+ owner).sorted,
          getMilestonesWithIssueCount(owner, name),
          getLabels(owner, name),
          hasWritePermission(owner, name, context.loginAccount),
          repository)
      } getOrElse NotFound
    }
  })

  get("/:owner/:repository/issues/new")(readableUsersOnly { repository =>
    defining(repository.owner, repository.name){ case (owner, name) =>
      issues.html.create(
          (getCollaborators(owner, name) :+ owner).sorted,
          getMilestones(owner, name),
          getLabels(owner, name),
          hasWritePermission(owner, name, context.loginAccount),
          repository)
    }
  })

  post("/:owner/:repository/issues/new", issueCreateForm)(readableUsersOnly { (form, repository) =>
    defining(repository.owner, repository.name){ case (owner, name) =>
      val writable = hasWritePermission(owner, name, context.loginAccount)
      val userName = context.loginAccount.get.userName

      // insert issue
      val issueId = createIssue(owner, name, userName, form.title, form.content,
        if(writable) form.assignedUserName else None,
        if(writable) form.milestoneId else None)

      // insert labels
      if(writable){
        form.labelNames.map { value =>
          val labels = getLabels(owner, name)
          value.split(",").foreach { labelName =>
            labels.find(_.labelName == labelName).map { label =>
              registerIssueLabel(owner, name, issueId, label.labelId)
            }
          }
        }
      }

      // record activity
      recordCreateIssueActivity(owner, name, userName, issueId, form.title)

      // notifications
      Notifier().toNotify(repository, issueId, form.content.getOrElse("")){
        Notifier.msgIssue(s"${baseUrl}/${owner}/${name}/issues/${issueId}")
      }

      redirect(s"/${owner}/${name}/issues/${issueId}")
    }
  })

  ajaxPost("/:owner/:repository/issues/edit/:id", issueEditForm)(readableUsersOnly { (form, repository) =>
    defining(repository.owner, repository.name){ case (owner, name) =>
      getIssue(owner, name, params("id")).map { issue =>
        if(isEditable(owner, name, issue.openedUserName)){
          updateIssue(owner, name, issue.issueId, form.title, form.content)
          redirect(s"/${owner}/${name}/issues/_data/${issue.issueId}")
        } else Unauthorized
      } getOrElse NotFound
    }
  })

  post("/:owner/:repository/issue_comments/new", commentForm)(readableUsersOnly { (form, repository) =>
    handleComment(form.issueId, Some(form.content), repository)() map { case (issue, id) =>
      redirect(s"/${repository.owner}/${repository.name}/${
        if(issue.isPullRequest) "pull" else "issues"}/${form.issueId}#comment-${id}")
    } getOrElse NotFound
  })

  post("/:owner/:repository/issue_comments/state", issueStateForm)(readableUsersOnly { (form, repository) =>
    handleComment(form.issueId, form.content, repository)() map { case (issue, id) =>
      redirect(s"/${repository.owner}/${repository.name}/${
        if(issue.isPullRequest) "pull" else "issues"}/${form.issueId}#comment-${id}")
    } getOrElse NotFound
  })

  ajaxPost("/:owner/:repository/issue_comments/edit/:id", commentForm)(readableUsersOnly { (form, repository) =>
    defining(repository.owner, repository.name){ case (owner, name) =>
      getComment(owner, name, params("id")).map { comment =>
        if(isEditable(owner, name, comment.commentedUserName)){
          updateComment(comment.commentId, form.content)
          redirect(s"/${owner}/${name}/issue_comments/_data/${comment.commentId}")
        } else Unauthorized
      } getOrElse NotFound
    }
  })

  ajaxGet("/:owner/:repository/issues/_data/:id")(readableUsersOnly { repository =>
    getIssue(repository.owner, repository.name, params("id")) map { x =>
      if(isEditable(x.userName, x.repositoryName, x.openedUserName)){
        params.get("dataType") collect {
          case t if t == "html" => issues.html.editissue(
              x.title, x.content, x.issueId, x.userName, x.repositoryName)
        } getOrElse {
          contentType = formats("json")
          org.json4s.jackson.Serialization.write(
              Map("title"   -> x.title,
                  "content" -> view.Markdown.toHtml(x.content getOrElse "No description given.",
                      repository, false, true)
              ))
        }
      } else Unauthorized
    } getOrElse NotFound
  })

  ajaxGet("/:owner/:repository/issue_comments/_data/:id")(readableUsersOnly { repository =>
    getComment(repository.owner, repository.name, params("id")) map { x =>
      if(isEditable(x.userName, x.repositoryName, x.commentedUserName)){
        params.get("dataType") collect {
          case t if t == "html" => issues.html.editcomment(
              x.content, x.commentId, x.userName, x.repositoryName)
        } getOrElse {
          contentType = formats("json")
          org.json4s.jackson.Serialization.write(
              Map("content" -> view.Markdown.toHtml(x.content,
                  repository, false, true)
              ))
        }
      } else Unauthorized
    } getOrElse NotFound
  })

  ajaxPost("/:owner/:repository/issues/:id/label/new")(collaboratorsOnly { repository =>
    defining(params("id").toInt){ issueId =>
      registerIssueLabel(repository.owner, repository.name, issueId, params("labelId").toInt)
      issues.html.labellist(getIssueLabels(repository.owner, repository.name, issueId))
    }
  })

  ajaxPost("/:owner/:repository/issues/:id/label/delete")(collaboratorsOnly { repository =>
    defining(params("id").toInt){ issueId =>
      deleteIssueLabel(repository.owner, repository.name, issueId, params("labelId").toInt)
      issues.html.labellist(getIssueLabels(repository.owner, repository.name, issueId))
    }
  })

  ajaxPost("/:owner/:repository/issues/:id/assign")(collaboratorsOnly { repository =>
    updateAssignedUserName(repository.owner, repository.name, params("id").toInt, assignedUserName("assignedUserName"))
    Ok("updated")
  })

  ajaxPost("/:owner/:repository/issues/:id/milestone")(collaboratorsOnly { repository =>
    updateMilestoneId(repository.owner, repository.name, params("id").toInt, milestoneId("milestoneId"))
    milestoneId("milestoneId").map { milestoneId =>
      getMilestonesWithIssueCount(repository.owner, repository.name)
          .find(_._1.milestoneId == milestoneId).map { case (_, openCount, closeCount) =>
        issues.milestones.html.progress(openCount + closeCount, closeCount, false)
      } getOrElse NotFound
    } getOrElse Ok()
  })

  post("/:owner/:repository/issues/batchedit/state")(collaboratorsOnly { repository =>
    defining(params.get("value")){ action =>
      executeBatch(repository) {
        handleComment(_, None, repository)( _ => action)
      }
    }
  })

  post("/:owner/:repository/issues/batchedit/label")(collaboratorsOnly { repository =>
    params("value").toIntOpt.map{ labelId =>
      executeBatch(repository) { issueId =>
        getIssueLabel(repository.owner, repository.name, issueId, labelId) getOrElse {
          registerIssueLabel(repository.owner, repository.name, issueId, labelId)
        }
      }
    } getOrElse NotFound
  })

  post("/:owner/:repository/issues/batchedit/assign")(collaboratorsOnly { repository =>
    defining(assignedUserName("value")){ value =>
      executeBatch(repository) {
        updateAssignedUserName(repository.owner, repository.name, _, value)
      }
    }
  })

  post("/:owner/:repository/issues/batchedit/milestone")(collaboratorsOnly { repository =>
    defining(milestoneId("value")){ value =>
      executeBatch(repository) {
        updateMilestoneId(repository.owner, repository.name, _, value)
      }
    }
  })

  val assignedUserName = (key: String) => params.get(key) filter (_.trim != "")
  val milestoneId: String => Option[Int] = (key: String) => params.get(key).flatMap(_.toIntOpt)

  private def isEditable(owner: String, repository: String, author: String)(implicit context: app.Context): Boolean =
    hasWritePermission(owner, repository, context.loginAccount) || author == context.loginAccount.get.userName

  private def executeBatch(repository: RepositoryService.RepositoryInfo)(execute: Int => Unit) = {
    params("checked").split(',') map(_.toInt) foreach execute
    redirect(s"/${repository.owner}/${repository.name}/issues")
  }

  /**
   * @see [[https://github.com/takezoe/gitbucket/wiki/CommentAction]]
   */
  private def handleComment(issueId: Int, content: Option[String], repository: RepositoryService.RepositoryInfo)
      (getAction: model.Issue => Option[String] =
           p1 => params.get("action").filter(_ => isEditable(p1.userName, p1.repositoryName, p1.openedUserName))) = {

    defining(repository.owner, repository.name){ case (owner, name) =>
      val userName = context.loginAccount.get.userName

      getIssue(owner, name, issueId.toString) map { issue =>
        val (action, recordActivity) =
          getAction(issue)
            .collect {
            case "close"  => true  -> (Some("close")  ->
              Some(if(issue.isPullRequest) recordClosePullRequestActivity _ else recordCloseIssueActivity _))
            case "reopen" => false -> (Some("reopen") ->
              Some(recordReopenIssueActivity _))
          }
            .map { case (closed, t) =>
            updateClosed(owner, name, issueId, closed)
            t
          }
            .getOrElse(None -> None)

        val commentId = content
          .map       ( _ -> action.map( _ + "_comment" ).getOrElse("comment") )
          .getOrElse ( action.get.capitalize -> action.get )
        match {
          case (content, action) => createComment(owner, name, userName, issueId, content, action)
        }

        // record activity
        content foreach {
          (if(issue.isPullRequest) recordCommentPullRequestActivity _ else recordCommentIssueActivity _)
          (owner, name, userName, issueId, _)
        }
        recordActivity foreach ( _ (owner, name, userName, issueId, issue.title) )

        // notifications
        Notifier() match {
          case f =>
            content foreach {
              f.toNotify(repository, issueId, _){
                Notifier.msgComment(s"${baseUrl}/${owner}/${name}/${
                  if(issue.isPullRequest) "pull" else "issues"}/${issueId}#comment-${commentId}")
              }
            }
            action foreach {
              f.toNotify(repository, issueId, _){
                Notifier.msgStatus(s"${baseUrl}/${owner}/${name}/issues/${issueId}")
              }
            }
        }

        issue -> commentId
      }
    }
  }

  private def searchIssues(filter: String, repository: RepositoryService.RepositoryInfo) = {
    defining(repository.owner, repository.name){ case (owner, repoName) =>
      val filterUser = Map(filter -> params.getOrElse("userName", ""))
      val page       = IssueSearchCondition.page(request)
      val sessionKey = Keys.Session.Issues(owner, repoName)

      // retrieve search condition
      val condition = session.putAndGet(sessionKey,
        if(request.hasQueryString) IssueSearchCondition(request)
        else session.getAs[IssueSearchCondition](sessionKey).getOrElse(IssueSearchCondition())
      )

      issues.html.list(
          searchIssue(condition, filterUser, false, (page - 1) * IssueLimit, IssueLimit, owner -> repoName),
          page,
          (getCollaborators(owner, repoName) :+ owner).sorted,
          getMilestones(owner, repoName),
          getLabels(owner, repoName),
          countIssue(condition.copy(state = "open"), filterUser, false, owner -> repoName),
          countIssue(condition.copy(state = "closed"), filterUser, false, owner -> repoName),
          countIssue(condition, Map.empty, false, owner -> repoName),
          context.loginAccount.map(x => countIssue(condition, Map("assigned" -> x.userName), false, owner -> repoName)),
          context.loginAccount.map(x => countIssue(condition, Map("created_by" -> x.userName), false, owner -> repoName)),
          countIssueGroupByLabels(owner, repoName, condition, filterUser),
          condition,
          filter,
          repository,
          hasWritePermission(owner, repoName, context.loginAccount))
    }
  }

}
