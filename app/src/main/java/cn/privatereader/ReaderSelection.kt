package cn.privatereader

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Event boundary between the Readium ActionMode and the shared editing workspace. */
class ReaderSelectionEvents {
 internal val channel=Channel<Pair<SourceAnchor,String>>(Channel.BUFFERED)
 internal val events=channel.receiveAsFlow()
 fun select(anchor:SourceAnchor,action:String){require(action in setOf("self","ai","card","cleanup","select"));channel.trySend(anchor to action)}
}
