package lila.app
package templating

import lila.api.Env.{ current ⇒ apiEnv }

object Environment
    extends scalaz.Identitys
    with scalaz.Options
    with scalaz.Booleans
    with StringHelper
    // with MarkdownHelper
    with AssetHelper
    // with UiHelper
    // with RequestHelper
    // with SettingHelper
    // with ConfigHelper 
    // with DateHelper 
    // with NumberHelper 
    // with JsonHelper
    // with PaginatorHelper
    // with FormHelper
    // with setup.SetupHelper
    with MessageHelper
    // with round.RoundHelper 
    with AiHelper
    with GameHelper
    with UserHelper
    // with forum.ForumHelper
    // with security.SecurityHelper
    with I18nHelper
    // with bookmark.BookmarkHelper
    with NotificationHelper {
  // with analyse.AnalyseHelper
  // with tournament.TournamentHelper 
  // with team.TeamHelper {

  def netDomain = apiEnv.Net.Domain
  def netBaseUrl = apiEnv.Net.BaseUrl

  def isDev = apiEnv.isDev
}
