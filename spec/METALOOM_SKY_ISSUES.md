# Detection Management
* Add a slider in the detection management view that controls the size of the thumbnails. I want to control the size and make them bigger or less big uh, I think the current size is the absolute minimum but I would expect it has three steps up in size so we can inspect the thumbnails in a more detailed way.
* It is currently required to click one cluster card before the control with the cursor keys is possible. Please make it so that the cursor key control automatically starts when pressing one cursor key. It should start at the top left corner.
* reduce the cluster card size by 50% in width.

# asset view 
* add a way to control the audio volume. There are currently no volume level controls (Only mute, unmute exists)
* Only metadata has a scroll bar in the details view or in the assets view. The other elements like whisper or locations do not have a scroll bar and also are not directly accessible in some cases when the view is overcrowded. I would like to have a a scroll bar and b the option to collapse or expand certain sections like whisper localization metadata. I think the description can also put, be put in this area. So it is a little bit more controllable what is being displayed. The collapse state and uncollapse state should be persisted in the local store
*  There should be a drag handle below the video so it can be resized and take more space up in the view or less space. And also of course um, increase in width and height accordingly. Currently there is no drag handle. Only the sidebar has a drag handle. We would like to add a drag handle so we can control the amount of space the video player takes.
* The color transcription section in the whisper area should move up and be visible in the timeline of the player. This should only be visible and clickable when the whisper area is expanded. Otherwise it should not show up. There should be an effect which fades in, fades out. These tiles in the player timeline currently the video-timeline-bar As a certain height and I would like fifty percent of the area being utilized for the whisper tiles and when the drawer or the section for the whisper whisper area is not visible those tiles are not shown in the timeline bar.
* You have not fixed the sidebar um, reduction issue. Or the sidebar size issue. You did not follow my instructions and collapse or hide the text label from the tabs. When a certain size is or when a certain size has been reached. I would like the tabs to only show up with a icon at a specific size so if it gets narrow or too narrow we only display the icons.
* The aria-label="Save" has a wrong styling is the text label and the icon is not clearly visible um, I think it needs to be brighter from the text or white or actually black I'm not sure what the problem is but the save button is not clearly legible
* In the sidebar, I can click on faces, but hovering over faces creates a strange flickering effect. Uh, please fix the flickering and additionally when I click on a uh, face the bounding box that is being displayed is totally wrong. Is it, it is elongated and has not the right shape for the face. Uh, it almost looks like it is I can't even explain it. It is wrong shape. Please uh, fix the face detection boxes. The height somehow seems to match, but I think it is just yeah, this is a scaling issue. If I increase the player size to uh, full screen, it has a different position. If I zoom right, zoom right, zoom into it has a different position. Uh, somehow the position calculation for the bounding box is wrong. Please fix it. Please also ensure that it works in different scales of the player and especially in combination with the resize functionality which is being added see video timeline bar



# Asset List 
* Don't show the MIME type in the asset list or the library list. The name of the file is sufficient. Add a small overlay on the right lower corner of the video which contains in in a small highlighted box the duration of the video hours minutes seconds

# UI Consistency

* Most of the views have a MUI typography icon and MUI typography caption for example the collections view has an icon and a label named collections in the top left corner of the or in the top bar of the view unfortunately other views like tags detection management tags tasks uploads assets and libraries do not follow the same design and lack sometimes the icon and in case for workflows it has a different font size. This is also the case for the management area monitoring pipelines spaces memory deny list ACL and so on basically all views have inconsistencies in the header

# Pipeline View

In the pipeline editor, the pipeline nodes lack a name. I see the type pipeline node element in the viewer, but I can't figure out what actual node type they are. Only the icon hints on the different type of the node. There is no text label within the node itself. Please add the label.

# Semantic Search / Transcript search
* So whispered transcript currently lacks uh, search. I'm not able to search the transcript for certain sections or keywords I would like uh, even a semantic search within the transcript bounds for one episode and of course also be able to search the transcripts semantically across all episodes please add a sidecar for this we need a TEI sidecar text embedding service with an embedding model e.g. BGE M3 or something similar please check Hugging Face something lightweight which is sufficient for our testing Add the sidecar setup to the sidecars folder and deploy the sidecar container on the metaloom.sky instance. Via Docker.


----------
# Asset View
* Clicking  on a video-timeline-transcript-tile should automatically scroll to the section in the whisper transcript uh, section and also start highlighting the transcript like it is done when I click on a phrase. Yeah, it should keep updating when the transcript or the video plays. Like it is done with a regular click on the transcript tile.
* The sidebar with breakpoint now works as expected with one limitation. I'm able to narrow the sidebar even so much that some icons vanish. They get clipped out of the view of the tabs. The, the tabs vanish. The tab icons vanish when I make the sidebar even more narrow. Uh, additionally, when I make the sidebar less narrow, I see the labels, but initially the labels are also clipped. Um, please find a way so that clipping or overflow does not happen. When this happens, it should prevent the sidebar from getting even more narrow.
* I'm not able to grab and drag the region tag markers in the timeline. I can't change the the region tag.