import MagneticButton from "./MagneticButton.tsx";
import {ArrowRight} from "baseui/icon";

export default function GetStartedButton () {
    return (
        <MagneticButton Label="Get Started" RightAppend={<ArrowRight />} />
    )
}

