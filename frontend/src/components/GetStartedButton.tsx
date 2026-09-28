import MagneticButton from "./MagneticButton.tsx";
import {ArrowRight} from "baseui/icon";

export default function GetStartedButton () {

    const handleLogin = () => {
        window.location.href = '/oauth2/authorization/cognito';
    };

    return (
        <span onClick={handleLogin}>
            <MagneticButton Label="Get Started" RightAppend={<ArrowRight />} />
        </span>
    )
}

